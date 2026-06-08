package server.bots.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotManager;
import server.bots.llm.BotLlmConfig;
import server.bots.planner.BotAutonomousPlanner;

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
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/despawn")) {
            int id = extractIdFromPath(path, 1);
            handleDespawn(ex, id);
        } else if ("POST".equals(method) && path.matches("/bots/\\d+/move")) {
            int id = extractIdFromPath(path, 1);
            handleMove(ex, id);
        } else {
            send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
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
