package server.bots.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.llm.BotLlmConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global, swappable system-prompt templates for the planner. The active template
 * is selected by a one-line {@code active.txt} in {@code plannerPromptsDir}; the
 * debug panel writes that file, and the planner reads it here. This keeps the
 * panel↔server contract file-based and mtime-cached, mirroring {@link ContextFileLoader}
 * (no REST API, no JVM poking).
 *
 * Layout under {@code <plannerPromptsDir>/}:
 *   active.txt              one line: the active template basename, e.g. "system_base"
 *   <name>.md               the system-prompt template (full prompt, with placeholders)
 *   <name>.fewshot.md       optional few-shot fragment for {{fewshot}}
 *
 * Templates support these placeholders: {@code {{name}} {{job}} {{soul}} {{fewshot}}}.
 * GOAL/observation live in the user prompt (see {@link PlannerPromptBuilder#buildPrompt}),
 * so they are intentionally not substituted here.
 *
 * When no active template is set (or it fails to load) {@link #render} returns null,
 * and the caller falls back to the built-in hardcoded prompt — so a missing/broken
 * library never breaks the planner.
 */
final class PlannerPromptLibrary {
    private static final Logger log = LoggerFactory.getLogger(PlannerPromptLibrary.class);

    private record Cached(long mtime, String content) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private PlannerPromptLibrary() {}

    /**
     * Render the active template with the given substitutions, or null if no usable
     * active template is configured (caller should fall back to the built-in prompt).
     */
    static String render(String botName, String job, String soul) {
        String active = readActiveName();
        if (active == null) return null;

        Path templatePath = Path.of(BotLlmConfig.plannerPromptsDir, active + ".md");
        String template = readCached(templatePath);
        if (template == null || template.isBlank()) return null;

        String fewshot = readCached(Path.of(BotLlmConfig.plannerPromptsDir, active + ".fewshot.md"));
        if (fewshot == null) fewshot = "";

        return template
                .replace("{{name}}", botName != null ? botName : "bot")
                .replace("{{job}}", job != null ? job : "adventurer")
                .replace("{{soul}}", soul != null ? soul.trim() : "")
                .replace("{{fewshot}}", fewshot.trim());
    }

    /** The active template basename from active.txt, or null if unset/blank/missing. */
    private static String readActiveName() {
        String content = readCached(Path.of(BotLlmConfig.plannerPromptsDir, "active.txt"));
        if (content == null) return null;
        String name = content.trim();
        if (name.isEmpty()) return null;
        // Guard against path tricks; templates are flat basenames only.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            log.warn("planner: ignoring suspicious active prompt name '{}'", name);
            return null;
        }
        return name;
    }

    /** mtime-cached read; returns null when the file is absent or unreadable. */
    private static String readCached(Path path) {
        String key = path.toAbsolutePath().toString();
        try {
            if (!Files.isRegularFile(path)) {
                CACHE.remove(key);
                return null;
            }
            long mtime = Files.getLastModifiedTime(path).toMillis();
            Cached cached = CACHE.get(key);
            if (cached != null && cached.mtime() == mtime) {
                return cached.content();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            CACHE.put(key, new Cached(mtime, content));
            return content;
        } catch (IOException e) {
            log.warn("planner: failed to read prompt file {}: {}", key, e.toString());
            Cached cached = CACHE.get(key);
            return cached != null ? cached.content() : null;
        }
    }
}
