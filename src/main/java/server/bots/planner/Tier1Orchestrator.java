package server.bots.planner;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotEntry;
import server.bots.llm.AnthropicClient;
import server.bots.llm.BotLlmConfig;
import server.bots.llm.SituationBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tier-1 of the 3-tier loop: the Claude Opus layer that AUTHORS GOAL.md. It is the only
 * component allowed to write goal CONTENT — the Tier-2 SLM harness only flips status
 * flags. Invoked at the two escalation signals raised by {@link BotAutonomousPlanner}:
 * <ul>
 *   <li><b>batch-complete</b> ({@code [LOOP] step=escalate}) — every goal done; author
 *       the next stretch.</li>
 *   <li><b>stuck</b> ({@code [LOOP] step=stuck}) — no completion progress for K ticks;
 *       review the current GOAL.md and rewrite to unstick.</li>
 * </ul>
 *
 * Authoring is grounded by {@link WorldIndex} (real grind spots / quests / gear) so Claude
 * never invents a map or item. The new file is written atomically (temp + ATOMIC_MOVE,
 * mirroring {@link GoalChecklist}) after backing up the prior GOAL.md to {@code GOAL.md.bak}.
 *
 * Threading: a dedicated single daemon thread ({@code bot-tier1}) serializes all Claude
 * calls (one slow/expensive request at a time, gentle on cost + rate limits), entirely off
 * both the game tick and the {@code bot-planner} pool. A per-bot in-flight gate stops the
 * every-K-tick stuck signal from stacking calls. Everything is swallowed — a Claude or
 * world-index failure leaves the existing GOAL.md untouched and the loop runs on as before.
 *
 * Disabled by default ({@link BotLlmConfig#tier1Enabled}) because it calls the paid API.
 * SOUL.md (the stable persona) is never written — only GOAL.md.
 */
public final class Tier1Orchestrator {
    private static final Logger log = LoggerFactory.getLogger(Tier1Orchestrator.class);

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bot-tier1");
        t.setDaemon(true);
        return t;
    });

    /** botCharId → authoring-in-flight, so we never queue two GOAL.md rewrites for one bot. */
    private static final ConcurrentHashMap<Integer, AtomicBoolean> INFLIGHT = new ConcurrentHashMap<>();

    /** botCharId → last Tier-1 attempt epochMs, for the per-bot cooldown (anti-runaway). */
    private static final ConcurrentHashMap<Integer, Long> LAST_RUN = new ConcurrentHashMap<>();

    // ---- Per-bot Tier-1 event history (newest last) — surfaced to the debug panel ----
    /** One Tier-1 authoring attempt: why it fired, what came of it, and how long it took. */
    public record Tier1Event(long epochMs, String reason, String result, int openGoals, long latencyMs, String model) {}

    private static final int EVENT_RING = 20;
    private static final ConcurrentHashMap<Integer, List<Tier1Event>> EVENTS = new ConcurrentHashMap<>();

    /** Up to {@code limit} most-recent Tier-1 events for a bot, oldest-first. Any thread. */
    public static List<Tier1Event> recentEvents(int botId, int limit) {
        List<Tier1Event> ring = EVENTS.get(botId);
        if (ring == null) return List.of();
        synchronized (ring) {
            int size = ring.size();
            int from = Math.max(0, size - Math.max(1, limit));
            return new ArrayList<>(ring.subList(from, size));
        }
    }

    private static void recordEvent(int botId, String reason, String result, int openGoals, long latencyMs) {
        List<Tier1Event> ring = EVENTS.computeIfAbsent(botId, k -> new ArrayList<>(EVENT_RING + 1));
        synchronized (ring) {
            ring.add(new Tier1Event(System.currentTimeMillis(), reason, result, openGoals, latencyMs, BotLlmConfig.tier1Model));
            while (ring.size() > EVENT_RING) ring.remove(0);
        }
    }

    // ---- Editable system prompt (an override file beats the built-in SYSTEM) ----
    private static volatile long promptMtime = -1;
    private static volatile String promptCache;

    private static Path systemPromptPath() {
        return Path.of(BotLlmConfig.plannerPromptsDir, "tier1-system.md");
    }

    /** Active system prompt: the editable override file if present + non-blank, else built-in {@link #SYSTEM}. */
    public static String systemPrompt() {
        Path p = systemPromptPath();
        try {
            if (Files.isRegularFile(p)) {
                long mt = Files.getLastModifiedTime(p).toMillis();
                String cached = promptCache;
                if (mt == promptMtime && cached != null) return cached;
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    promptMtime = mt;
                    promptCache = body;
                    return body;
                }
            }
        } catch (IOException e) {
            log.info("tier1: system-prompt read failed, using built-in: {}", e.toString());
        }
        return SYSTEM;
    }

    /** "file" if the editable override is active, else "builtin". */
    public static String systemPromptSource() {
        Path p = systemPromptPath();
        try {
            return (Files.isRegularFile(p) && !Files.readString(p, StandardCharsets.UTF_8).isBlank()) ? "file" : "builtin";
        } catch (IOException e) {
            return "builtin";
        }
    }

    /** Atomically write the override file (blank content reverts to the built-in by deleting it). */
    public static boolean writeSystemPrompt(String content) {
        Path p = systemPromptPath();
        try {
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);
            if (content == null || content.isBlank()) {
                Files.deleteIfExists(p);
            } else {
                Path tmp = p.resolveSibling("tier1-system.md.tmp");
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            promptMtime = -1;       // invalidate cache
            promptCache = null;
            return true;
        } catch (IOException e) {
            log.warn("tier1: system-prompt write failed: {}", e.toString());
            return false;
        }
    }

    static final String SYSTEM =
            "You are the Tier-1 planner for an autonomous MapleStory (GMS v83) character. "
            + "Your job: author the character's next GOAL.md — its short list of objectives for the "
            + "next stretch of play. A separate small model reads this file each tick and drives the "
            + "body; it can only flip goals done and choose among these actions: GRIND, GRIND_AT, "
            + "TRAVEL_TO, BUY_POTIONS, SELL, REST_IN_TOWN, FOLLOW_OWNER, STOP, IDLE.\n\n"
            + "Write GOAL.md in EXACTLY this structure and nothing else:\n\n"
            + "# GOAL — <name> (today)\n\n"
            + "## Priorities (top first)\n"
            + "1. <one-line objective>\n"
            + "2. ...\n\n"
            + "## Checklist\n"
            + "- [ ] g1: <objective> <!-- status:open -->\n"
            + "- [ ] g2: <objective> <!-- status:open -->\n\n"
            + "## Notes\n"
            + "<one or two short lines of guidance for the body model>\n\n"
            + "Rules:\n"
            + "- 3 to 6 checklist goals, ids g1..gN, each ending with the literal comment "
            + "<!-- status:open --> verbatim.\n"
            + "- Each goal MUST be checkable from game state alone (e.g. \"Reach level 40\", "
            + "\"Keep 50+ HP pots on hand\", \"Sell junk when the bag is near full\"). No vague goals.\n"
            + "- Ground every concrete map, quest, or item reference ONLY in the WORLD-INDEX data "
            + "provided. Never invent a map id, quest name, or item name. If no grounding is given, "
            + "keep goals generic (level/pots/survival).\n"
            + "- Advance the character from where it is now. Respect the persona in SOUL.\n"
            + "- Use the character name given in the prompt for the header.\n"
            + "- Output ONLY the markdown file. No code fences, no commentary.";

    private Tier1Orchestrator() {}

    /**
     * Queue a GOAL.md re-authoring for one bot. No-op when Tier-1 is disabled, the API key
     * is absent, or an authoring for this bot is already in flight. Returns immediately —
     * the Claude call runs on the dedicated {@code bot-tier1} thread.
     */
    public static void escalate(BotEntry entry, String reason) {
        if (!BotLlmConfig.tier1Enabled) return;
        if (entry == null || entry.getBot() == null) return;
        Character bot = entry.getBot();
        int id = bot.getId();

        // Per-bot cooldown — the anti-runaway gate. A stuck bot's signal re-fires every K
        // ticks; without this it re-triggers a PAID call each time. Consume the window on
        // every attempt so auto (escalate/stuck) and manual triggers are throttled uniformly.
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, BotLlmConfig.tier1MinIntervalMs);
        Long last = LAST_RUN.get(id);
        if (last != null && now - last < cooldown) {
            if (BotLlmConfig.debugLog) {
                log.info("tier1: {} on cooldown ({}s left) — skipping re-author",
                        bot.getName(), (cooldown - (now - last)) / 1000);
            }
            return;
        }
        LAST_RUN.put(id, now);

        if (!AnthropicClient.isConfigured()) {
            log.warn("[LOOP] step=tier1 bot={} skipped=no-api-key", bot.getName());
            recordEvent(id, reason, "skipped:no-key", 0, 0);
            return;
        }
        AtomicBoolean gate = INFLIGHT.computeIfAbsent(id, k -> new AtomicBoolean(false));
        if (!gate.compareAndSet(false, true)) return;   // already authoring for this bot
        EXEC.submit(() -> {
            try {
                author(entry, reason);
            } catch (Throwable t) {
                log.warn("tier1: author failed for {}: {}", bot.getName(), t.toString());
            } finally {
                gate.set(false);
            }
        });
    }

    /** Drop per-bot in-flight + event + cooldown state when a bot is removed. */
    public static void unregister(int botId) {
        INFLIGHT.remove(botId);
        EVENTS.remove(botId);
        LAST_RUN.remove(botId);
    }

    private static void author(BotEntry entry, String reason) {
        Character bot = entry.getBot();
        String botName = bot.getName();
        int botId = bot.getId();
        int level = bot.getLevel();
        int jobId = bot.getJob() != null ? bot.getJob().getId() : 0;

        String soul = ContextFileLoader.loadSoul(botName);
        String currentGoal = ContextFileLoader.loadGoal(botName);
        String situation = SituationBuilder.buildForPlanner(entry);
        String grounding = gatherGrounding(level, jobId);

        String prompt = buildPrompt(botName, reason, soul, currentGoal, situation, grounding);

        long t0 = System.currentTimeMillis();
        Optional<String> raw = AnthropicClient.complete(prompt, systemPrompt(), BotLlmConfig.tier1MaxTokens);
        long ms = System.currentTimeMillis() - t0;
        if (raw.isEmpty()) {
            log.info("[LOOP] step=tier1 bot={} reason=\"{}\" result=no-response ({}ms)", botName, reason, ms);
            recordEvent(botId, reason, "no-response", 0, ms);
            return;
        }

        String newGoal = stripFences(raw.get());
        if (newGoal.isBlank() || GoalChecklist.parse(newGoal).isEmpty()) {
            // Refuse to overwrite a real GOAL.md with something that has no parseable goal lines.
            log.info("[LOOP] step=tier1 bot={} reason=\"{}\" result=unusable ({}ms) raw={}",
                    botName, reason, ms, abbrev(raw.get(), 200));
            recordEvent(botId, reason, "unusable", 0, ms);
            return;
        }

        int open = (int) GoalChecklist.parse(newGoal).stream().filter(g -> "open".equals(g.status())).count();
        if (writeGoal(botName, newGoal)) {
            log.info("[LOOP] step=tier1 bot={} reason=\"{}\" result=authored openGoals={} ({}ms)",
                    botName, reason, open, ms);
            recordEvent(botId, reason, "authored", open, ms);
        } else {
            recordEvent(botId, reason, "write-failed", open, ms);
        }
    }

    /** Assemble world-index grounding for the bot's level/job. Sections present only when non-empty. */
    static String gatherGrounding(int level, int jobId) {
        StringBuilder sb = new StringBuilder(1024);
        String grind = WorldIndex.grind(level, level + 8);
        if (!grind.isBlank()) {
            sb.append("## Grind spots (level ").append(level).append('-').append(level + 8).append(")\n")
                    .append(grind).append('\n');
        }
        if (jobId > 0) {
            String quests = WorldIndex.questroute(level, level + 6, jobId);
            if (!quests.isBlank()) sb.append("## Quests routable now\n").append(quests).append('\n');
        }
        String family = jobFamily(jobId);
        if (family != null) {
            String gear = WorldIndex.gear(level, family);
            if (!gear.isBlank()) sb.append("## Gear upgrades (best per slot)\n").append(gear).append('\n');
        }
        return sb.toString();
    }

    static String buildPrompt(String botName, String reason, String soul,
                              String currentGoal, String situation, String grounding) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Character name: ").append(botName).append('\n');
        sb.append("Reason for re-authoring: ").append(reason).append("\n\n");
        sb.append("=== SOUL (persona — do not change) ===\n")
                .append(soul == null || soul.isBlank() ? "(none)" : soul).append("\n\n");
        sb.append("=== Current GOAL.md (just finished / stuck) ===\n")
                .append(currentGoal == null || currentGoal.isBlank() ? "(none)" : currentGoal).append("\n\n");
        sb.append("=== Current game state ===\n")
                .append(situation == null || situation.isBlank() ? "(unavailable)" : situation).append("\n\n");
        sb.append("=== WORLD-INDEX grounding (authoritative — cite only these) ===\n")
                .append(grounding.isBlank() ? "(none available)\n" : grounding).append('\n');
        sb.append("Author the next GOAL.md now. Output only the markdown.");
        return sb.toString();
    }

    /** MapleStory job-id branch → world-index gear job family. null = beginner/unknown (skip gear). */
    private static String jobFamily(int jobId) {
        return switch (jobId / 100) {
            case 1 -> "warrior";
            case 2 -> "mage";
            case 3 -> "bowman";
            case 4 -> "thief";
            case 5 -> "pirate";
            default -> null;
        };
    }

    /** Strip a leading ```lang fence + trailing ``` if the model wrapped the file in one. */
    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
        }
        return t.strip();
    }

    /**
     * Atomically replace {@code <plannerDataDir>/<botName>/GOAL.md} with {@code content},
     * backing up the prior file to {@code GOAL.md.bak} first (so a bad rewrite is reversible).
     * Mirrors {@link GoalChecklist}'s temp + ATOMIC_MOVE pattern; invalidates the loader cache.
     */
    private static boolean writeGoal(String botName, String content) {
        Path goalPath = Path.of(BotLlmConfig.plannerDataDir, botName, "GOAL.md");
        try {
            Path dir = goalPath.getParent();
            if (dir != null) Files.createDirectories(dir);
            if (Files.isRegularFile(goalPath)) {
                Files.copy(goalPath, goalPath.resolveSibling("GOAL.md.bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = goalPath.resolveSibling("GOAL.md.tmp");
            Files.writeString(tmp, content.endsWith("\n") ? content : content + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, goalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ContextFileLoader.invalidate(botName, "GOAL.md");
            return true;
        } catch (IOException e) {
            log.warn("tier1[{}] GOAL.md write failed: {}", botName, e.toString());
            return false;
        }
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
