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
 * Loads per-bot personality (SOUL.md) and daily-goal (GOAL.md) context files from
 * disk. Layout: {@code <plannerDataDir>/<botName>/SOUL.md} and {@code GOAL.md}.
 *
 * Files are cached and re-read only when their on-disk mtime changes, so the
 * planner picks up edits without a server restart (no file-watcher thread — the
 * planner polls these every decision tick, which is cheap because we only stat()
 * + re-read on change). Missing files return an empty string; the planner copes
 * with a bot that has no SOUL/GOAL yet.
 */
public final class ContextFileLoader {
    private static final Logger log = LoggerFactory.getLogger(ContextFileLoader.class);

    private record Cached(long mtime, String content) {}

    // keyed by absolute path string
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private ContextFileLoader() {}

    public static String loadSoul(String botName) {
        return load(botName, "SOUL.md");
    }

    public static String loadGoal(String botName) {
        return load(botName, "GOAL.md");
    }

    /** Drop the cache entry for a file so the next load re-reads from disk
     *  (used after GoalChecklist writes GOAL.md, in case the mtime is unchanged). */
    static void invalidate(String botName, String fileName) {
        if (botName == null || botName.isBlank()) return;
        Path path = Path.of(BotLlmConfig.plannerDataDir, botName, fileName);
        CACHE.remove(path.toAbsolutePath().toString());
    }

    private static String load(String botName, String fileName) {
        if (botName == null || botName.isBlank()) return "";
        Path path = Path.of(BotLlmConfig.plannerDataDir, botName, fileName);
        String key = path.toAbsolutePath().toString();
        try {
            if (!Files.isRegularFile(path)) {
                CACHE.remove(key);
                return "";
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
            log.warn("planner: failed to read {}: {}", key, e.toString());
            Cached cached = CACHE.get(key);
            return cached != null ? cached.content() : "";
        }
    }
}
