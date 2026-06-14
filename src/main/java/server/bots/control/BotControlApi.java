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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

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
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
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
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
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
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
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
