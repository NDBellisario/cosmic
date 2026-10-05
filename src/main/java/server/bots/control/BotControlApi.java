package server.bots.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotManager;
import server.bots.llm.AnthropicClient;
import server.bots.llm.BotLlmConfig;
import server.bots.planner.BotAutonomousPlanner;
import server.bots.planner.Tier1Orchestrator;
import server.bots.planner.WorldIndex;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.ItemInformationProvider;
import server.TimerManager;
import tools.DatabaseConnection;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight HTTP control API for the debug panel.
 * Bound to 127.0.0.1 only; never 0.0.0.0.
 *
 * Endpoints (contract is fixed — the panel's bots.ts proxy depends on it):
 *   GET  /health
 *   GET  /config                 Tier-1 (Claude GOAL.md authoring) settings + apiKeyPresent
 *   POST /config                 body: any subset of {tier1Enabled,tier1Model,anthropicEndpoint,tier1MaxTokens,tier1MinIntervalMs,worldIndexDir}
 *   GET  /tier1/prompt           current Tier-1 system prompt (editable file or built-in)
 *   PUT  /tier1/prompt           body: {"content":"..."} — edit the Tier-1 system prompt (blank reverts to built-in)
 *   POST /bots/{id}/tier1/reauthor   force a Tier-1 GOAL.md re-author now
 *   GET  /bots/{id}/tier1/events     per-bot Tier-1 authoring history (reason/result/latency)
 *   POST /bots/{id}/decide       force an immediate Tier-2 (SLM) decision
 *   GET  /bots
 *   POST /bots/spawn          body: {"name":"...","mapId":optional-int}
 *   POST /bots/{id}/despawn
 *   GET  /bots/{id}/decisions?limit=N
 */
public final class BotControlApi {
    private static final Logger log = LoggerFactory.getLogger(BotControlApi.class);
    private static volatile HttpServer server;

    private BotControlApi() {}

    /** Start the API server. Safe to call multiple times (idempotent). */
    public static synchronized void start() {
        if (!BotLlmConfig.botControlApiEnabled) return;
        if (server != null) return;

        int port = BotLlmConfig.botControlApiPort;
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
            s.createContext("/health",         BotControlApi::handleHealth);
            s.createContext("/config",         BotControlApi::handleConfig);
            s.createContext("/tier1",          BotControlApi::handleTier1);
            s.createContext("/bots/spawn",     BotControlApi::handleSpawn);
            s.createContext("/bots",           BotControlApi::handleBots);
            s.createContext("/maps",           BotControlApi::handleMaps);
            s.createContext("/worldindex",     BotControlApi::handleWorldIndex);
            s.createContext("/characters",     BotControlApi::handleCharacters);
            // Single-threaded executor: all requests are brief reads + game-thread submits.
            s.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bot-control-api");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            log.info("bot control API listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            log.warn("bot control API failed to bind on port {}: {} — API unavailable", port, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Auth helper
    // -------------------------------------------------------------------------

    /** Returns true if the request passes auth (or auth is disabled). Sends 401 and returns false otherwise. */
    private static boolean checkAuth(HttpExchange ex) throws IOException {
        String token = BotLlmConfig.botControlApiToken;
        if (token == null || token.isBlank()) return true;
        String header = ex.getRequestHeaders().getFirst("X-Panel-Token");
        if (token.equals(header)) return true;
        send(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
        return false;
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /** GET /health → {"ok":true,"plannerEnabled":<bool>,"model":"<model>"} */
    private static void handleHealth(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ok\":true,\"plannerEnabled\":");
        sb.append(BotLlmConfig.plannerEnabled);
        sb.append(",\"model\":\"");
        sb.append(jsonEscape(BotLlmConfig.model));
        sb.append("\"}");
        send(ex, 200, sb.toString());
    }

    /**
     * GET  /config → current Tier-1 (Claude-backed GOAL.md authoring) settings.
     * POST /config → apply any subset of the mutable fields, then return the new state.
     * The Anthropic API key is never read or written here — only its presence is
     * reported (apiKeyPresent), sourced from the ANTHROPIC_API_KEY env var.
     */
    private static void handleConfig(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        if ("GET".equals(method)) {
            send(ex, 200, configJson());
            return;
        }
        if (!"POST".equals(method)) {
            send(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Boolean enabled = extractBooleanField(body, "tier1Enabled");
        if (enabled != null) BotLlmConfig.tier1Enabled = enabled;
        String model = extractStringField(body, "tier1Model");
        if (model != null && !model.isBlank()) BotLlmConfig.tier1Model = model;
        String endpoint = extractStringField(body, "anthropicEndpoint");
        if (endpoint != null && !endpoint.isBlank()) BotLlmConfig.anthropicEndpoint = endpoint;
        Integer maxTokens = extractIntField(body, "tier1MaxTokens");
        if (maxTokens != null && maxTokens > 0) BotLlmConfig.tier1MaxTokens = maxTokens;
        Integer minInterval = extractIntField(body, "tier1MinIntervalMs");
        if (minInterval != null && minInterval >= 0) BotLlmConfig.tier1MinIntervalMs = minInterval;
        String worldIndexDir = extractStringField(body, "worldIndexDir");
        if (worldIndexDir != null && !worldIndexDir.isBlank()) BotLlmConfig.worldIndexDir = worldIndexDir;
        log.info("bot control API: tier-1 config updated (enabled={}, model={})",
                BotLlmConfig.tier1Enabled, BotLlmConfig.tier1Model);
        send(ex, 200, configJson());
    }

    /** Serialize the live Tier-1 config (+ read-only apiKeyPresent) — shared by GET and POST. */
    private static String configJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ok\":true");
        sb.append(",\"tier1Enabled\":").append(BotLlmConfig.tier1Enabled);
        sb.append(",\"tier1Model\":\"").append(jsonEscape(BotLlmConfig.tier1Model)).append("\"");
        sb.append(",\"anthropicEndpoint\":\"").append(jsonEscape(BotLlmConfig.anthropicEndpoint)).append("\"");
        sb.append(",\"tier1MaxTokens\":").append(BotLlmConfig.tier1MaxTokens);
        sb.append(",\"tier1MinIntervalMs\":").append(BotLlmConfig.tier1MinIntervalMs);
        sb.append(",\"worldIndexDir\":\"").append(jsonEscape(BotLlmConfig.worldIndexDir)).append("\"");
        sb.append(",\"apiKeyPresent\":").append(AnthropicClient.isConfigured());
        sb.append("}");
        return sb.toString();
    }

    /**
     * Routes /bots/* requests.
     * The HttpServer context "/bots" matches everything under /bots (and /bots itself).
     * We further dispatch by method + path here.
     */
    private static void handleBots(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath(); // e.g. /bots, /bots/123/despawn, /bots/123/decisions

        // Strip trailing slash for uniformity.
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);

        if ("GET".equals(method) && "/bots".equals(path)) {
            handleListBots(ex);
        } else if ("GET".equals(method) && path.matches("/bots/\\d+/decisions")) {
            int id = extractIdFromPath(path, 1);
            handleDecisions(ex, id);
        } else if ("GET".equals(method) && path.matches("/bots/\\d+/brain")) {
            int id = extractIdFromPath(path, 1);
            handleBrain(ex, id);
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/despawn")) {
            int id = extractIdFromPath(path, 1);
            handleDespawn(ex, id);
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/move")) {
            int id = extractIdFromPath(path, 1);
            handleMove(ex, id);
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/tier1/reauthor")) {
            handleReauthor(ex, extractIdFromPath(path, 2));
        } else if ("GET".equals(method) && path.matches("/bots/\\d+/tier1/events")) {
            handleTier1Events(ex, extractIdFromPath(path, 2));
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/decide")) {
            handleForceDecide(ex, extractIdFromPath(path, 1));
        } else {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
    }

    /**
     * GET  /tier1/prompt → {"ok":true,"source":"file|builtin","prompt":"<text>"}
     * PUT  /tier1/prompt   body {"content":"..."} — overwrite the editable Tier-1 system
     *      prompt (blank content reverts to the built-in). Returns the new state.
     */
    private static void handleTier1(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        if (!"/tier1/prompt".equals(path)) {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
            return;
        }
        if ("PUT".equals(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String content = extractStringField(body, "content");
            if (content == null) {
                send(ex, 400, "{\"ok\":false,\"error\":\"body must include {\\\"content\\\":\\\"...\\\"}\"}");
                return;
            }
            Tier1Orchestrator.writeSystemPrompt(content);
            log.info("bot control API: tier-1 system prompt updated ({} chars)", content.length());
            send(ex, 200, tier1PromptJson());
        } else if ("GET".equals(method)) {
            send(ex, 200, tier1PromptJson());
        } else {
            send(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
        }
    }

    private static String tier1PromptJson() {
        return "{\"ok\":true,\"source\":\"" + jsonEscape(Tier1Orchestrator.systemPromptSource())
                + "\",\"prompt\":\"" + jsonEscape(Tier1Orchestrator.systemPrompt()) + "\"}";
    }

    /** POST /bots/{id}/tier1/reauthor — force a Tier-1 GOAL.md re-author for a running bot. */
    private static void handleReauthor(HttpExchange ex, int botCharId) throws IOException {
        boolean ok = BotAutonomousPlanner.forceReauthor(botCharId, "manual re-author (panel)");
        if (!ok) { send(ex, 404, "{\"ok\":false,\"error\":\"bot not registered\"}"); return; }
        send(ex, 200, "{\"ok\":true,\"queued\":true}");
    }

    /** POST /bots/{id}/decide — force an immediate Tier-2 (SLM) decision for a running bot. */
    private static void handleForceDecide(HttpExchange ex, int botCharId) throws IOException {
        boolean ok = BotAutonomousPlanner.forceDecision(botCharId, "manual decision (panel)");
        if (!ok) { send(ex, 404, "{\"ok\":false,\"error\":\"bot not registered\"}"); return; }
        send(ex, 200, "{\"ok\":true,\"queued\":true}");
    }

    /** GET /bots/{id}/tier1/events?limit=N → [{ts,reason,result,openGoals,latencyMs,model}, ...] newest-last */
    private static void handleTier1Events(HttpExchange ex, int botCharId) throws IOException {
        int limit = 20;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("limit=")) {
                    try { limit = Integer.parseInt(param.substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        limit = Math.max(1, Math.min(limit, 100));
        List<Tier1Orchestrator.Tier1Event> events = Tier1Orchestrator.recentEvents(botCharId, limit);
        StringBuilder sb = new StringBuilder(events.size() * 96 + 2);
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            Tier1Orchestrator.Tier1Event e = events.get(i);
            sb.append("{\"ts\":").append(e.epochMs());
            sb.append(",\"reason\":\"").append(jsonEscape(e.reason())).append("\"");
            sb.append(",\"result\":\"").append(jsonEscape(e.result())).append("\"");
            sb.append(",\"openGoals\":").append(e.openGoals());
            sb.append(",\"latencyMs\":").append(e.latencyMs());
            sb.append(",\"model\":\"").append(jsonEscape(e.model())).append("\"}");
        }
        sb.append(']');
        send(ex, 200, sb.toString());
    }

    /** GET /maps/{mapId}/mobs → [{x,y,name}, ...] live monster positions for the mini-map. */
    private static void handleMaps(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        if ("GET".equalsIgnoreCase(ex.getRequestMethod()) && path.matches("/maps/\\d+/mobs")) {
            int mapId = extractIdFromPath(path, 1);
            List<BotManager.MapMobDto> mobs = BotManager.getInstance().listMapMonsters(mapId);
            StringBuilder sb = new StringBuilder(mobs.size() * 40 + 2);
            sb.append('[');
            for (int i = 0; i < mobs.size(); i++) {
                if (i > 0) sb.append(',');
                BotManager.MapMobDto m = mobs.get(i);
                sb.append("{\"x\":").append(m.x()).append(",\"y\":").append(m.y())
                        .append(",\"name\":\"").append(jsonEscape(m.name())).append("\"}");
            }
            sb.append(']');
            send(ex, 200, sb.toString());
        } else {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
    }

    /** POST /bots/spawn — handled by its own context so it doesn't collide with /bots/{id}/... */
    private static void handleSpawn(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = extractStringField(body, "name");
        if (name == null || name.isBlank()) {
            send(ex, 400, "{\"ok\":false,\"error\":\"body must include {\\\"name\\\":\\\"...\\\"}\"}" );
            return;
        }
        Integer mapId = extractIntField(body, "mapId");

        BotManager.OwnerlessSpawnResult result = BotManager.getInstance().spawnOfflineBotByName(name, mapId);
        if (!result.success()) {
            send(ex, 400, buildErrorJson(result.errorMessage()));
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ok\":true,\"id\":").append(result.botCharId());
        sb.append(",\"name\":\"").append(jsonEscape(result.botName())).append("\"");
        sb.append(",\"mapId\":").append(result.mapId());
        sb.append(",\"mapName\":\"").append(jsonEscape(result.mapName())).append("\"");
        sb.append("}");
        send(ex, 200, sb.toString());
    }

    /** GET /bots → [{id,name,mapId,mapName,x,y,hp,maxHp,level,status,idle}, ...] */
    private static void handleListBots(HttpExchange ex) throws IOException {
        List<BotManager.BotStateDto> bots = BotManager.getInstance().listBotStates();
        StringBuilder sb = new StringBuilder(bots.size() * 128 + 2);
        sb.append('[');
        for (int i = 0; i < bots.size(); i++) {
            if (i > 0) sb.append(',');
            appendBotStateJson(sb, bots.get(i));
        }
        sb.append(']');
        send(ex, 200, sb.toString());
    }

    /** POST /bots/{id}/despawn */
    private static void handleDespawn(HttpExchange ex, int botCharId) throws IOException {
        boolean found = BotManager.getInstance().despawnBot(botCharId);
        if (!found) {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
        } else {
            send(ex, 200, "{\"ok\":true}");
        }
    }

    /** POST /bots/{id}/move  body {"mapId":int} — live-move a spawned bot to another map. */
    private static void handleMove(HttpExchange ex, int botCharId) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Integer mapId = extractIntField(body, "mapId");
        if (mapId == null) {
            send(ex, 400, "{\"ok\":false,\"error\":\"body must include {\\\"mapId\\\":int}\"}");
            return;
        }
        BotManager.OwnerlessSpawnResult result = BotManager.getInstance().moveBotToMap(botCharId, mapId);
        if (!result.success()) {
            send(ex, 400, buildErrorJson(result.errorMessage()));
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ok\":true,\"id\":").append(result.botCharId());
        sb.append(",\"name\":\"").append(jsonEscape(result.botName())).append("\"");
        sb.append(",\"mapId\":").append(result.mapId());
        sb.append(",\"mapName\":\"").append(jsonEscape(result.mapName())).append("\"");
        sb.append("}");
        send(ex, 200, sb.toString());
    }

    /** GET /bots/{id}/decisions?limit=N → [{ts,action,reason}, ...] newest-last */
    private static void handleDecisions(HttpExchange ex, int botCharId) throws IOException {
        int limit = 20;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("limit=")) {
                    try { limit = Integer.parseInt(param.substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        limit = Math.max(1, Math.min(limit, 100));

        List<BotAutonomousPlanner.DecisionRecord> records = BotAutonomousPlanner.recentDecisions(botCharId, limit);
        StringBuilder sb = new StringBuilder(records.size() * 64 + 2);
        sb.append('[');
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(',');
            BotAutonomousPlanner.DecisionRecord r = records.get(i);
            sb.append("{\"ts\":").append(r.epochMs());
            sb.append(",\"action\":\"").append(jsonEscape(r.action())).append("\"");
            sb.append(",\"reason\":\"").append(jsonEscape(r.reason())).append("\"");
            sb.append("}");
        }
        sb.append(']');
        send(ex, 200, sb.toString());
    }

    /**
     * GET /bots/{id}/brain → the most-recent full planner cycle snapshot, or {} if none yet.
     * JSON shape:
     * {
     *   "epochMs": <long>,
     *   "situation": "<string>",
     *   "input": "<string>",
     *   "system": "<string>",
     *   "rawOutput": "<string>",
     *   "action": "<string>",
     *   "reason": "<string>",
     *   "params": "<string>",
     *   "nextRunEpochMs": <long>
     * }
     */
    private static void handleBrain(HttpExchange ex, int botCharId) throws IOException {
        BotAutonomousPlanner.BrainSnapshot snap = BotAutonomousPlanner.brainSnapshot(botCharId);
        if (snap == null) {
            send(ex, 200, "{}");
            return;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"epochMs\":").append(snap.epochMs());
        sb.append(",\"situation\":\"").append(jsonEscape(snap.situation())).append("\"");
        sb.append(",\"input\":\"").append(jsonEscape(snap.input())).append("\"");
        sb.append(",\"system\":\"").append(jsonEscape(snap.system())).append("\"");
        sb.append(",\"rawOutput\":\"").append(jsonEscape(snap.rawOutput())).append("\"");
        sb.append(",\"action\":\"").append(jsonEscape(snap.action())).append("\"");
        sb.append(",\"reason\":\"").append(jsonEscape(snap.reason())).append("\"");
        sb.append(",\"params\":\"").append(jsonEscape(snap.params())).append("\"");
        sb.append(",\"nextRunEpochMs\":").append(snap.nextRunEpochMs());
        sb.append("}");
        send(ex, 200, sb.toString());
    }

    // -------------------------------------------------------------------------
    // World-index browsing (GET only) — proxies query.py --json straight through.
    // -------------------------------------------------------------------------

    /**
     * GET /worldindex/{maps|mobs|mob-locations|spawns|items|gear|sources}
     * Returns the raw JSON produced by world-index/query.py (or "[]" if empty).
     */
    private static void handleWorldIndex(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"method not allowed\"}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());

        String json;
        switch (path) {
            case "/worldindex/maps"          -> json = WorldIndex.queryJson("map", q.getOrDefault("q", ""));
            case "/worldindex/mobs"          -> json = WorldIndex.queryJson("mob", q.getOrDefault("q", ""));
            case "/worldindex/mob-locations" -> json = WorldIndex.queryJson("mobmaps", q.getOrDefault("q", ""));
            case "/worldindex/spawns"        -> json = WorldIndex.queryJson("spawns", q.getOrDefault("mapId", ""));
            case "/worldindex/items"         -> json = WorldIndex.queryJson("equip", q.getOrDefault("q", ""));
            case "/worldindex/sources"       -> json = WorldIndex.queryJson("sources", q.getOrDefault("q", ""));
            case "/worldindex/gear" -> {
                List<String> args = new ArrayList<>();
                args.add("--level"); args.add(q.getOrDefault("level", ""));
                args.add("--job");   args.add(q.getOrDefault("job", ""));
                String slot = q.get("slot");
                if (slot != null && !slot.isBlank()) { args.add("--slot"); args.add(slot); }
                json = WorldIndex.queryJson("gear", args.toArray(new String[0]));
            }
            default -> {
                send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
                return;
            }
        }
        if (json == null || json.isBlank()) json = "[]";
        sendJson(ex, 200, json);
    }

    // -------------------------------------------------------------------------
    // Character inventory browsing + item granting (online live / offline DB).
    // -------------------------------------------------------------------------

    /**
     * GET  /characters?q=<name>            → search chars (online + DB), ~25 results
     * GET  /characters/{id}/inventory      → list a char's items (live if online, else DB)
     * POST /characters/{id}/items          body {"itemId":int,"quantity":int} → give an item
     */
    private static void handleCharacters(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);

        if ("GET".equals(method) && "/characters".equals(path)) {
            handleCharacterSearch(ex);
        } else if ("GET".equals(method) && path.matches("/characters/\\d+/inventory")) {
            handleCharacterInventory(ex, extractIdFromPath(path, 1));
        } else if ("POST".equals(method) && path.matches("/characters/\\d+/items")) {
            handleCharacterGiveItem(ex, extractIdFromPath(path, 1));
        } else {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
    }

    /** GET /characters?q=<name> → [{id,name,level,job,online}, ...] */
    private static void handleCharacterSearch(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String term = q.getOrDefault("q", "").trim();

        // id -> [name, level, job, online]
        Map<Integer, Object[]> rows = new LinkedHashMap<>();

        // Online players first (so they win on overlap and are flagged online).
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel c : w.getChannels()) {
                PlayerStorage ps = c.getPlayerStorage();
                if (ps == null) continue;
                for (Character chr : ps.getAllCharacters()) {
                    if (chr == null) continue;
                    if (!term.isEmpty() && !chr.getName().toLowerCase().contains(term.toLowerCase())) continue;
                    rows.putIfAbsent(chr.getId(),
                            new Object[]{chr.getName(), chr.getLevel(), chr.getJob().getId(), Boolean.TRUE});
                    if (rows.size() >= 25) break;
                }
            }
        }

        // Fill remaining slots from the DB.
        if (rows.size() < 25) {
            String sql = "SELECT id,name,level,job FROM characters WHERE name LIKE ? ORDER BY name LIMIT 25";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement psq = con.prepareStatement(sql)) {
                psq.setString(1, "%" + term + "%");
                try (ResultSet rs = psq.executeQuery()) {
                    while (rs.next() && rows.size() < 25) {
                        int id = rs.getInt("id");
                        if (rows.containsKey(id)) continue;
                        rows.put(id, new Object[]{rs.getString("name"), rs.getInt("level"),
                                rs.getInt("job"), Boolean.FALSE});
                    }
                }
            } catch (Exception e) {
                log.warn("character search DB query failed: {}", e.toString());
            }
        }

        StringBuilder sb = new StringBuilder(rows.size() * 64 + 2);
        sb.append('[');
        boolean first = true;
        for (Map.Entry<Integer, Object[]> e : rows.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Object[] r = e.getValue();
            sb.append("{\"id\":").append(e.getKey());
            sb.append(",\"name\":\"").append(jsonEscape((String) r[0])).append("\"");
            sb.append(",\"level\":").append((int) r[1]);
            sb.append(",\"job\":").append((int) r[2]);
            sb.append(",\"online\":").append((boolean) r[3]);
            sb.append("}");
        }
        sb.append(']');
        sendJson(ex, 200, sb.toString());
    }

    /** GET /characters/{id}/inventory */
    private static void handleCharacterInventory(HttpExchange ex, int charId) throws IOException {
        Character online = findOnlineCharById(charId);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        if (online != null) {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"ok\":true,\"online\":true,\"items\":[");
            boolean first = true;
            InventoryType[] types = {InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                    InventoryType.ETC, InventoryType.CASH, InventoryType.EQUIPPED};
            for (InventoryType type : types) {
                Inventory inv = online.getInventory(type);
                if (inv == null) continue;
                for (Item it : inv.list()) {
                    if (!first) sb.append(',');
                    first = false;
                    boolean equip = (it instanceof Equip);
                    sb.append("{\"inventoryType\":\"").append(type.name()).append("\"");
                    sb.append(",\"position\":").append(it.getPosition());
                    sb.append(",\"itemId\":").append(it.getItemId());
                    sb.append(",\"name\":\"").append(jsonEscape(ii.getName(it.getItemId()))).append("\"");
                    sb.append(",\"quantity\":").append(it.getQuantity());
                    sb.append(",\"equip\":").append(equip);
                    sb.append("}");
                }
            }
            sb.append("]}");
            sendJson(ex, 200, sb.toString());
            return;
        }

        // Offline: must exist in the characters table.
        if (!charExistsInDb(charId)) {
            send(ex, 404, "{\"ok\":false,\"error\":\"character not found\"}");
            return;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"ok\":true,\"online\":false,\"items\":[");
        String sql = "SELECT inventorytype,position,itemid,quantity FROM inventoryitems " +
                "WHERE characterid=? ORDER BY inventorytype,position";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    int invType = rs.getInt("inventorytype");
                    int itemId = rs.getInt("itemid");
                    boolean equip = (invType == InventoryType.EQUIP.getType()
                            || invType == InventoryType.EQUIPPED.getType());
                    InventoryType t = InventoryType.getByType((byte) invType);
                    sb.append("{\"inventoryType\":\"").append(t.name()).append("\"");
                    sb.append(",\"position\":").append(rs.getInt("position"));
                    sb.append(",\"itemId\":").append(itemId);
                    sb.append(",\"name\":\"").append(jsonEscape(ii.getName(itemId))).append("\"");
                    sb.append(",\"quantity\":").append(rs.getInt("quantity"));
                    sb.append(",\"equip\":").append(equip);
                    sb.append("}");
                }
            }
        } catch (Exception e) {
            log.warn("character inventory DB query failed for {}: {}", charId, e.toString());
            send(ex, 500, "{\"ok\":false,\"error\":\"db query failed\"}");
            return;
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    /** POST /characters/{id}/items body {"itemId":int,"quantity":int} */
    private static void handleCharacterGiveItem(HttpExchange ex, int charId) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Integer itemIdBox = extractIntField(body, "itemId");
        if (itemIdBox == null) {
            send(ex, 400, "{\"ok\":false,\"error\":\"body must include {\\\"itemId\\\":int}\"}");
            return;
        }
        int itemId = itemIdBox;
        Integer qtyBox = extractIntField(body, "quantity");
        int quantity = (qtyBox == null) ? 1 : qtyBox;
        if (quantity < 1) {
            send(ex, 400, "{\"ok\":false,\"error\":\"quantity must be >= 1\"}");
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        String itemName = ii.getName(itemId);
        if (itemName == null) itemName = "item " + itemId;

        Character online = findOnlineCharById(charId);
        if (online != null) {
            final Client client = online.getClient();
            final AtomicBoolean result = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(1);
            TimerManager.getInstance().schedule(() -> {
                try {
                    boolean ok = InventoryManipulator.addById(client, itemId, (short) quantity,
                            online.getName(), -1, (short) 0, -1L);
                    result.set(ok);
                } catch (Exception e) {
                    log.warn("live addById failed for char {}: {}", charId, e.toString());
                } finally {
                    latch.countDown();
                }
            }, 0L);
            boolean completed;
            try {
                completed = latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                completed = false;
            }
            if (!completed) {
                send(ex, 200, "{\"ok\":false,\"error\":\"game-thread timeout\"}");
                return;
            }
            if (!result.get()) {
                send(ex, 200, "{\"ok\":false,\"error\":\"inventory full or add failed\"}");
                return;
            }
            String msg = "gave " + quantity + "x " + itemName + " to " + online.getName() + " (live)";
            send(ex, 200, "{\"ok\":true,\"mode\":\"live\",\"message\":\"" + jsonEscape(msg) + "\"}");
            return;
        }

        // Offline: insert into the DB.
        if (!charExistsInDb(charId)) {
            send(ex, 404, "{\"ok\":false,\"error\":\"character not found\"}");
            return;
        }
        try {
            String charName = giveItemOffline(charId, itemId, quantity, ii);
            String msg = "inserted " + quantity + "x " + itemName + " into " + charName + "'s DB (offline)";
            send(ex, 200, "{\"ok\":true,\"mode\":\"db\",\"message\":\"" + jsonEscape(msg) + "\"}");
        } catch (Exception e) {
            log.warn("offline item insert failed for char {}: {}", charId, e.toString());
            send(ex, 500, "{\"ok\":false,\"error\":\"db insert failed\"}");
        }
    }

    /** Insert an item directly into a character's DB inventory; returns the char's name. */
    private static String giveItemOffline(int charId, int itemId, int quantity, ItemInformationProvider ii)
            throws Exception {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        byte invType = type.getType();
        boolean equip = (invType == InventoryType.EQUIP.getType()) || ItemConstants.isEquipment(itemId);
        if (equip) invType = InventoryType.EQUIP.getType();

        String charName = "char " + charId;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE id=?")) {
                ps.setInt(1, charId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) charName = rs.getString("name");
                }
            }
            int nextSlot = nextSlot(con, charId, invType);

            String insItem = "INSERT INTO inventoryitems " +
                    "(type,characterid,accountid,itemid,inventorytype,position,quantity,owner,petid,flag,expiration,giftFrom) " +
                    "VALUES (?,?,NULL,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = con.prepareStatement(insItem, Statement.RETURN_GENERATED_KEYS)) {
                // `type` is the ItemFactory scope discriminator: 1 = INVENTORY (character-scoped),
                // matching ItemFactory.INVENTORY. It is NOT the inventory type.
                ps.setInt(1, ItemFactory.INVENTORY.getValue());
                ps.setInt(2, charId);
                ps.setInt(3, itemId);
                ps.setInt(4, invType);
                ps.setInt(5, nextSlot);
                ps.setInt(6, equip ? 1 : quantity);
                ps.setString(7, "");
                ps.setInt(8, -1);
                ps.setInt(9, 0);
                ps.setLong(10, -1L);
                ps.setString(11, "");
                ps.executeUpdate();

                if (equip) {
                    int invItemId;
                    try (ResultSet gk = ps.getGeneratedKeys()) {
                        if (!gk.next()) throw new IllegalStateException("no generated key for inventoryitems");
                        invItemId = gk.getInt(1);
                    }
                    Equip eq = (Equip) ii.getEquipById(itemId);
                    String insEquip = "INSERT INTO inventoryequipment " +
                            "(inventoryitemid,upgradeslots,level,str,dex,`int`,luk,hp,mp,watk,matk,wdef,mdef,acc,avoid,hands,speed,jump,locked,vicious,itemlevel,itemexp,ringid) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                    try (PreparedStatement pe = con.prepareStatement(insEquip)) {
                        pe.setInt(1, invItemId);
                        pe.setInt(2, eq.getUpgradeSlots());
                        pe.setInt(3, eq.getLevel());
                        pe.setInt(4, eq.getStr());
                        pe.setInt(5, eq.getDex());
                        pe.setInt(6, eq.getInt());
                        pe.setInt(7, eq.getLuk());
                        pe.setInt(8, eq.getHp());
                        pe.setInt(9, eq.getMp());
                        pe.setInt(10, eq.getWatk());
                        pe.setInt(11, eq.getMatk());
                        pe.setInt(12, eq.getWdef());
                        pe.setInt(13, eq.getMdef());
                        pe.setInt(14, eq.getAcc());
                        pe.setInt(15, eq.getAvoid());
                        pe.setInt(16, eq.getHands());
                        pe.setInt(17, eq.getSpeed());
                        pe.setInt(18, eq.getJump());
                        pe.setInt(19, 0);                 // locked
                        pe.setInt(20, eq.getVicious());
                        pe.setInt(21, eq.getItemLevel());
                        pe.setInt(22, 0);                 // itemexp
                        pe.setInt(23, eq.getRingId());
                        pe.executeUpdate();
                    }
                }
            }
        }
        return charName;
    }

    /** Next free slot (1-based) for a character's inventory type. */
    private static int nextSlot(Connection con, int charId, int invType) throws Exception {
        String sql = "SELECT COALESCE(MAX(position),0)+1 FROM inventoryitems WHERE characterid=? AND inventorytype=?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, charId);
            ps.setInt(2, invType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    /** Find an online character by id across all worlds/channels, or null. */
    private static Character findOnlineCharById(int charId) {
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel c : w.getChannels()) {
                PlayerStorage ps = c.getPlayerStorage();
                if (ps == null) continue;
                Character chr = ps.getCharacterById(charId);
                if (chr != null) return chr;
            }
        }
        return null;
    }

    /** True if a character id exists in the characters table. */
    private static boolean charExistsInDb(int charId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM characters WHERE id=?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("charExistsInDb failed for {}: {}", charId, e.toString());
            return false;
        }
    }

    /** Parse a URL query string into a map of URL-decoded key/value pairs. */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(urlDecode(pair), "");
            } else {
                out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    /** Send a body that is already JSON (no re-escaping). */
    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, json);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void appendBotStateJson(StringBuilder sb, BotManager.BotStateDto d) {
        sb.append("{\"id\":").append(d.id());
        sb.append(",\"name\":\"").append(jsonEscape(d.name())).append("\"");
        sb.append(",\"mapId\":").append(d.mapId());
        sb.append(",\"mapName\":\"").append(jsonEscape(d.mapName())).append("\"");
        sb.append(",\"x\":").append(d.x());
        sb.append(",\"y\":").append(d.y());
        sb.append(",\"hp\":").append(d.hp());
        sb.append(",\"maxHp\":").append(d.maxHp());
        sb.append(",\"level\":").append(d.level());
        sb.append(",\"exp\":").append(d.exp());
        sb.append(",\"expPercent\":").append(d.expPercent());
        sb.append(",\"status\":\"").append(jsonEscape(d.status())).append("\"");
        sb.append(",\"idle\":").append(d.idle());
        sb.append("}");
    }

    private static String buildErrorJson(String msg) {
        return "{\"ok\":false,\"error\":\"" + jsonEscape(msg != null ? msg : "unknown error") + "\"}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Extract the numeric segment at position {@code segIndex} (0-based) counting from the end. */
    private static int extractIdFromPath(String path, int segFromEnd) {
        String[] parts = path.split("/");
        // /bots/123/despawn → parts = ["", "bots", "123", "despawn"]
        int idx = parts.length - 1 - segFromEnd;
        try { return Integer.parseInt(parts[idx]); } catch (Exception e) { return -1; }
    }

    /**
     * Minimal JSON string-field extraction (mirrors OllamaClient style).
     * Finds {@code "key":"value"} and returns the unescaped value string, or null.
     */
    private static String extractStringField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && java.lang.Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }

    /** Extract an integer field value from a JSON string, or null if absent/unparseable. */
    private static Integer extractIntField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && java.lang.Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (java.lang.Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (i == start) return null;
        try { return Integer.parseInt(json.substring(start, i)); } catch (NumberFormatException e) { return null; }
    }

    /** Extract a boolean field value from a JSON string, or null if absent/unparseable. */
    private static Boolean extractBooleanField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && java.lang.Character.isWhitespace(json.charAt(i))) i++;
        if (json.startsWith("true", i)) return Boolean.TRUE;
        if (json.startsWith("false", i)) return Boolean.FALSE;
        return null;
    }

    /** JSON-escape a string value (mirrors OllamaClient.jsonEscape). */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
