package server.bots.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.llm.BotLlmConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Read-only bridge to the external world-index grounding harness
 * ({@code ../world-index/query.py}, Python + SQLite). The Tier-1 planner uses it to
 * fetch authoritative facts (grind spots, routable quests, gear) so Claude never has to
 * invent a map id, quest name, or item.
 *
 * Best-effort by design: a missing python, an unbuilt {@code world.sqlite}, a non-zero
 * exit, or a timeout all return {@code ""} — Tier-1 then authors a GOAL.md without
 * grounding tables rather than failing. Never throws. The default text (tab-aligned)
 * output is fed straight into the prompt; Claude reads the tables fine, so no parsing.
 */
final class WorldIndex {
    private static final Logger log = LoggerFactory.getLogger(WorldIndex.class);

    private WorldIndex() {}

    /** Ranked grind spots for a level band. */
    static String grind(int min, int max) {
        return run(List.of("grind", "--min", Integer.toString(min), "--max", Integer.toString(max), "--limit", "8"));
    }

    /** Quests routable for a level band, job-aware. */
    static String questroute(int min, int max, int jobId) {
        return run(List.of("questroute", "--min", Integer.toString(min), "--max", Integer.toString(max),
                "--job", Integer.toString(jobId), "--limit", "12"));
    }

    /** Best equippable gear per slot for a level + job family ("warrior"|"mage"|...). */
    static String gear(int level, String jobFamily) {
        return run(List.of("gear", "--level", Integer.toString(level), "--job", jobFamily, "--limit", "12"));
    }

    private static String run(List<String> args) {
        List<String> cmd = new ArrayList<>(args.size() + 2);
        cmd.add(BotLlmConfig.worldIndexPython);
        cmd.add("query.py");
        cmd.addAll(args);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd)
                    .directory(Path.of(BotLlmConfig.worldIndexDir).toFile())
                    .redirectErrorStream(false)
                    .start();
            StringBuilder sb = new StringBuilder(2048);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() != 0) {
                if (BotLlmConfig.debugLog) log.info("world-index {} exit={}", args.get(0), p.exitValue());
                return "";
            }
            return sb.toString();
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            log.info("world-index {} failed: {}", args.isEmpty() ? "?" : args.get(0), e.toString());
            return "";
        }
    }
}
