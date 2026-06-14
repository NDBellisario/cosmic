package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal raw-HTTP client for the Anthropic Messages API ({@code POST /v1/messages}),
 * used by the Tier-1 planner ({@link server.bots.planner.Tier1Orchestrator}) to author
 * GOAL.md. Mirrors {@link OllamaClient}: hand-built JSON body (no Jackson, per project
 * rule), every failure swallowed into {@link Optional#empty()} so a Claude hiccup never
 * crashes anything. The API key is read from {@code MAPLE_AGENT_ANTHROPIC_API_KEY} (a
 * project-dedicated key so Tier-1 spend bills separately), falling back to
 * {@code ANTHROPIC_API_KEY} if that is unset — never stored in config.
 *
 * Opus 4.8 with adaptive thinking ({@code {"type":"adaptive"}}) is used; thinking blocks
 * stream back with empty text by default, so the response parser extracts the text block
 * specifically and skips them.
 */
public final class AnthropicClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    private AnthropicClient() {}

    /**
     * Resolve the API key: the project-dedicated {@code MAPLE_AGENT_ANTHROPIC_API_KEY}
     * (kept separate so Tier-1 usage bills on its own key), else {@code ANTHROPIC_API_KEY}.
     * Returns null if neither is set.
     */
    private static String apiKey() {
        String key = System.getenv("MAPLE_AGENT_ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("ANTHROPIC_API_KEY");
        return key == null || key.isBlank() ? null : key;
    }

    /** True iff an API key is present — lets callers skip work cleanly when unconfigured. */
    public static boolean isConfigured() {
        return apiKey() != null;
    }

    /**
     * One non-streaming Messages call. Returns the assistant's text (thinking stripped),
     * or empty on any failure (no key, non-200, timeout, parse miss).
     */
    public static Optional<String> complete(String prompt, String system, int maxTokens) {
        String key = apiKey();
        if (key == null) {
            log.warn("anthropic: no API key (set MAPLE_AGENT_ANTHROPIC_API_KEY) — call skipped");
            return Optional.empty();
        }
        String body = buildBody(prompt, system, maxTokens);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(BotLlmConfig.anthropicEndpoint + "/v1/messages"))
                    .timeout(Duration.ofMillis(BotLlmConfig.tier1TimeoutMs))
                    .header("content-type", "application/json")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            log.warn("anthropic: request build failed: {}", e.toString());
            return Optional.empty();
        }
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("anthropic: HTTP {} body: {}", resp.statusCode(), abbrev(resp.body(), 300));
                return Optional.empty();
            }
            String text = extractTextBlock(resp.body());
            if (text == null || text.isBlank()) return Optional.empty();
            return Optional.of(text.trim());
        } catch (java.net.http.HttpTimeoutException te) {
            log.info("anthropic: timeout after {}ms", BotLlmConfig.tier1TimeoutMs);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("anthropic: send failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String buildBody(String prompt, String system, int maxTokens) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"model\":\"").append(OllamaClient.jsonEscape(BotLlmConfig.tier1Model)).append("\",");
        sb.append("\"max_tokens\":").append(maxTokens).append(',');
        sb.append("\"thinking\":{\"type\":\"adaptive\"},");
        if (system != null && !system.isEmpty()) {
            sb.append("\"system\":\"").append(OllamaClient.jsonEscape(system)).append("\",");
        }
        sb.append("\"messages\":[{\"role\":\"user\",\"content\":\"")
                .append(OllamaClient.jsonEscape(prompt)).append("\"}]");
        sb.append('}');
        return sb.toString();
    }

    /**
     * Pull the first text content block out of a Messages response:
     * {@code {"content":[{"type":"thinking",...},{"type":"text","text":"..."}],...}}.
     * Anchors on {@code "type":"text"} (won't match a {@code "thinking"} block), then
     * decodes the following {@code "text":"..."} field. Hand-rolled, no JSON dependency.
     */
    static String extractTextBlock(String json) {
        if (json == null) return null;
        int typeIdx = json.indexOf("\"type\":\"text\"");
        if (typeIdx < 0) return null;
        int field = json.indexOf("\"text\":", typeIdx);   // the text field, not the "text" type value
        if (field < 0) return null;
        int i = field + "\"text\":".length();
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
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                            } catch (NumberFormatException ignored) {}
                            i += 4;
                        }
                    }
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

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
