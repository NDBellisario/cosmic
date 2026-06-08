package server.bots.planner;

import java.util.HashMap;
import java.util.Map;

/**
 * One parsed planner decision: {@code {"action","reason","params"}}.
 *
 * Parsing is hand-rolled (no Jackson/Gson) to match the project's existing
 * dependency-free JSON handling in {@code OllamaClient}. The SLM is asked for a
 * single JSON object; small models routinely wrap it in prose or code fences, so
 * the parser is forgiving: it locates the first balanced {@code { ... }} block,
 * then extracts only the three fields it cares about. Anything malformed yields
 * {@code null} and the caller keeps the current mode (the spec's fallback rule).
 */
final class PlannerDecision {
    final PlannerAction action;
    final String reason;
    final Map<String, String> params;

    private PlannerDecision(PlannerAction action, String reason, Map<String, String> params) {
        this.action = action;
        this.reason = reason;
        this.params = params;
    }

    /** Convenience accessor for an integer param, defaulting when absent/unparseable. */
    int intParam(String key, int dflt) {
        String v = params.get(key);
        if (v == null) return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * Parse a raw SLM response into a decision, or null if no usable action was found.
     * Tolerates surrounding prose / markdown fences by scanning to the first object.
     */
    static PlannerDecision parse(String raw) {
        if (raw == null) return null;
        String obj = extractFirstObject(raw);
        if (obj == null) return null;

        String actionStr = extractStringField(obj, "action");
        PlannerAction action = PlannerAction.fromString(actionStr);
        if (action == null) return null;

        String reason = extractStringField(obj, "reason");
        if (reason == null) reason = "";

        Map<String, String> params = extractParams(obj);
        return new PlannerDecision(action, reason.trim(), params);
    }

    /** Returns the substring spanning the first balanced {@code {...}}, ignoring braces inside strings. */
    static String extractFirstObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Extract a JSON string value for {@code "key"}. Handles standard escapes.
     * Returns null if the key is absent or its value is not a string.
     */
    static String extractStringField(String json, String key) {
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
        boolean esc = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    /**
     * Pull the {@code "params"} sub-object into a flat string map. Values may be
     * numbers or strings; both are captured as their raw textual form (numbers
     * unquoted). Missing/empty params yields an empty map.
     */
    static Map<String, String> extractParams(String json) {
        Map<String, String> out = new HashMap<>();
        int k = json.indexOf("\"params\"");
        if (k < 0) return out;
        int brace = json.indexOf('{', k);
        if (brace < 0) return out;
        String inner = extractFirstObject(json.substring(brace));
        if (inner == null || inner.length() <= 2) return out;
        // Strip outer braces; scan key:value pairs.
        String body = inner.substring(1, inner.length() - 1);
        int i = 0;
        while (i < body.length()) {
            // find key
            int q = body.indexOf('"', i);
            if (q < 0) break;
            int q2 = q + 1;
            StringBuilder keyB = new StringBuilder();
            boolean esc = false;
            for (; q2 < body.length(); q2++) {
                char c = body.charAt(q2);
                if (esc) { keyB.append(c); esc = false; }
                else if (c == '\\') esc = true;
                else if (c == '"') break;
                else keyB.append(c);
            }
            int colon = body.indexOf(':', q2);
            if (colon < 0) break;
            int v = colon + 1;
            while (v < body.length() && Character.isWhitespace(body.charAt(v))) v++;
            String value;
            if (v < body.length() && body.charAt(v) == '"') {
                // quoted value
                int v2 = v + 1;
                StringBuilder valB = new StringBuilder();
                esc = false;
                for (; v2 < body.length(); v2++) {
                    char c = body.charAt(v2);
                    if (esc) { valB.append(c); esc = false; }
                    else if (c == '\\') esc = true;
                    else if (c == '"') break;
                    else valB.append(c);
                }
                value = valB.toString();
                i = v2 + 1;
            } else {
                // bare number / literal up to comma or end
                int v2 = v;
                while (v2 < body.length() && body.charAt(v2) != ',' && body.charAt(v2) != '}') v2++;
                value = body.substring(v, v2).trim();
                i = v2 + 1;
            }
            out.put(keyB.toString(), value);
        }
        return out;
    }
}
