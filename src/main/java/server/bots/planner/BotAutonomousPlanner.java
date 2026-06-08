package server.bots.planner;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotEntry;
import server.bots.BotManager;
import server.bots.llm.BotLlmConfig;
import server.bots.llm.OllamaClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The autonomous decision layer. For each registered bot that is currently idle
 * (no owner command driving it), the planner periodically asks the SLM what to do
 * next, parses the structured JSON answer, and dispatches it through the existing
 * {@link BotManager} public hooks. It never reimplements combat/nav — it only
 * chooses among {@link PlannerAction}s the framework already knows how to execute.
 *
 * Threading mirrors {@code BotLlmReplyManager}: a dedicated daemon executor runs
 * all Ollama calls (never the bot tick thread), a global semaphore caps concurrent
 * inferences, and a per-bot in-flight gate prevents queueing two decisions for one
 * bot. Every failure is swallowed; an SLM hiccup must never crash a tick or change
 * the bot's mode (fallback = keep current mode).
 *
 * Lifecycle: {@code BotManager} calls {@link #register} when a bot spawns and
 * {@link #unregister} when it is removed.
 */
public final class BotAutonomousPlanner {
    private static final Logger log = LoggerFactory.getLogger(BotAutonomousPlanner.class);

    private static final ScheduledExecutorService EXEC = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "bot-planner");
        t.setDaemon(true);
        return t;
    });

    /** Caps concurrent planner inferences; shares the same intent as the chat gate but is separate. */
    private static final Semaphore GATE = new Semaphore(Math.max(1, BotLlmConfig.maxConcurrentGlobal));

    /** Registered bots eligible for autonomous decisions, keyed by bot char id. */
    private static final ConcurrentHashMap<Integer, BotEntry> REGISTERED = new ConcurrentHashMap<>();
    /** Per-bot in-flight gate so we never queue more than one decision per bot. */
    private static final ConcurrentHashMap<Integer, AtomicBoolean> INFLIGHT = new ConcurrentHashMap<>();

    // ---- Decision ring buffer -----------------------------------------------
    /** One recorded planner decision. Immutable so it's safe to hand to HTTP thread. */
    public record DecisionRecord(long epochMs, String action, String reason) {}

    /** Max decisions retained per bot. */
    private static final int RING_CAPACITY = 20;
    /** botCharId → ring buffer (newest at tail). Written from planner executor, read from HTTP thread. */
    private static final ConcurrentHashMap<Integer, List<DecisionRecord>> DECISIONS = new ConcurrentHashMap<>();

    /**
     * Returns up to {@code limit} most-recent decisions for {@code botId} in oldest-first order
     * (i.e. newest last, matching the API contract). Safe to call from any thread.
     */
    public static List<DecisionRecord> recentDecisions(int botId, int limit) {
        List<DecisionRecord> ring = DECISIONS.get(botId);
        if (ring == null) return Collections.emptyList();
        synchronized (ring) {
            int size = ring.size();
            int from = Math.max(0, size - Math.max(1, limit));
            return new ArrayList<>(ring.subList(from, size));
        }
    }

    private static void recordDecision(int botId, String action, String reason) {
        List<DecisionRecord> ring = DECISIONS.computeIfAbsent(botId, k -> new ArrayList<>(RING_CAPACITY + 1));
        synchronized (ring) {
            ring.add(new DecisionRecord(System.currentTimeMillis(), action, reason != null ? reason : ""));
            while (ring.size() > RING_CAPACITY) {
                ring.remove(0);
            }
        }
    }
    // -------------------------------------------------------------------------

    /** Single recurring sweep that evaluates all idle registered bots. Started lazily. */
    private static volatile boolean started = false;

    private BotAutonomousPlanner() {}

    /** Register a freshly-spawned bot. Idempotent; starts the sweep loop on first call. */
    public static void register(BotEntry entry) {
        if (entry == null || entry.getBot() == null) return;
        REGISTERED.put(entry.getBot().getId(), entry);
        ensureStarted();
    }

    /** Drop a bot (removed / disconnected) from autonomous control. */
    public static void unregister(BotEntry entry) {
        if (entry == null || entry.getBot() == null) return;
        int id = entry.getBot().getId();
        REGISTERED.remove(id);
        INFLIGHT.remove(id);
        DECISIONS.remove(id);
    }

    /**
     * Force an immediate re-evaluation for one bot, bypassing the interval. Use for
     * critical events (death, HP critical + no pots, goal item acquired, player
     * conversation, new map, owner logged off). Still respects the enabled flag,
     * idle gating, and the in-flight gate.
     */
    public static void triggerNow(BotEntry entry, String eventReason) {
        if (!BotLlmConfig.plannerEnabled || entry == null || entry.getBot() == null) return;
        if (!REGISTERED.containsKey(entry.getBot().getId())) return;
        submit(entry, eventReason);
    }

    private static void ensureStarted() {
        if (started) return;
        synchronized (BotAutonomousPlanner.class) {
            if (started) return;
            long period = Math.max(1000L, BotLlmConfig.plannerIntervalMs);
            EXEC.scheduleWithFixedDelay(BotAutonomousPlanner::sweep, period, period, TimeUnit.MILLISECONDS);
            started = true;
            log.info("planner: sweep loop started (interval {}ms)", period);
        }
    }

    /** Periodic pass: queue a decision for each idle, registered bot. */
    private static void sweep() {
        if (!BotLlmConfig.plannerEnabled) return;
        for (BotEntry entry : REGISTERED.values()) {
            try {
                submit(entry, null);
            } catch (Throwable t) {
                log.warn("planner: sweep submit failed: {}", t.toString());
            }
        }
    }

    /**
     * Gate + dispatch one decision onto the planner executor. Skips bots that are
     * not idle (owner commands and any active mode win), already have a decision in
     * flight, or when the global concurrency cap is saturated.
     */
    private static void submit(BotEntry entry, String eventReason) {
        if (!BotLlmConfig.plannerEnabled) return;
        Character bot = entry.getBot();
        if (bot == null || bot.getMap() == null) return;

        // Owner commands and any active mode take priority — planner only fills idle.
        if (!entry.isIdleForPlanner()) return;

        int id = bot.getId();
        AtomicBoolean inflight = INFLIGHT.computeIfAbsent(id, k -> new AtomicBoolean(false));
        if (!inflight.compareAndSet(false, true)) return;

        if (!GATE.tryAcquire()) {
            inflight.set(false);
            return;
        }

        EXEC.submit(() -> {
            try {
                decide(entry, eventReason);
            } catch (Throwable t) {
                log.warn("planner: decide failed for {}: {}", bot.getName(), t.toString());
            } finally {
                GATE.release();
                inflight.set(false);
            }
        });
    }

    /** Build prompt → call SLM → parse → dispatch. Off-tick. */
    private static void decide(BotEntry entry, String eventReason) {
        Character bot = entry.getBot();
        String botName = bot.getName();

        String soul = ContextFileLoader.loadSoul(botName);
        String goal = ContextFileLoader.loadGoal(botName);
        String system = PlannerPromptBuilder.buildSystem(entry, soul);
        String prompt = PlannerPromptBuilder.buildPrompt(entry, goal, eventReason);

        long t0 = System.currentTimeMillis();
        if (BotLlmConfig.debugLog) {
            log.info("planner[{}] system: {}", botName, system);
            log.info("planner[{}] prompt:\n{}", botName, prompt);
        }

        Optional<String> raw = OllamaClient.generateLong(prompt, system, BotLlmConfig.plannerMaxTokens);
        long elapsed = System.currentTimeMillis() - t0;
        if (raw.isEmpty()) {
            if (BotLlmConfig.debugLog) log.info("planner[{}] no response ({}ms) — keeping current mode", botName, elapsed);
            return;
        }

        PlannerDecision decision = PlannerDecision.parse(raw.get());
        if (decision == null) {
            log.info("planner[{}] unparseable response ({}ms), keeping current mode: {}",
                    botName, elapsed, raw.get());
            return;
        }

        // Re-check idle: the owner may have issued a command while the SLM was thinking.
        if (!entry.isIdleForPlanner()) {
            if (BotLlmConfig.debugLog) {
                log.info("planner[{}] decided {} but bot no longer idle — owner command wins",
                        botName, decision.action);
            }
            return;
        }

        log.info("planner[{}] decision={} reason=\"{}\" ({}ms)",
                botName, decision.action, decision.reason, elapsed);
        recordDecision(bot.getId(), decision.action.name(), decision.reason);
        apply(entry, decision);
    }

    /** Map a parsed decision onto the existing BotManager hooks. */
    private static void apply(BotEntry entry, PlannerDecision d) {
        BotManager bm = BotManager.getInstance();
        Character bot = entry.getBot();
        switch (d.action) {
            case GRIND -> bm.issueGrind(entry);
            case GRIND_AT -> {
                int mapId = d.intParam("mapId", -1);
                if (mapId < 0 || bot.getMapId() == mapId) {
                    bm.issueGrind(entry);
                } else {
                    // Cross-map autonomous travel is not yet wired (Phase 4+). Grind here
                    // rather than stalling, and surface the unmet intent in the log.
                    log.info("planner[{}] GRIND_AT map {} requested but cross-map travel unavailable; grinding here",
                            bot.getName(), mapId);
                    bm.issueGrind(entry);
                }
            }
            case TRAVEL_TO -> {
                int mapId = d.intParam("mapId", -1);
                if (mapId >= 0 && bot.getMapId() != mapId) {
                    log.info("planner[{}] TRAVEL_TO map {} requested but cross-map travel unavailable; idling",
                            bot.getName(), mapId);
                }
                // No-op on current map / unavailable travel: leave bot idle for next round.
            }
            case FOLLOW_OWNER -> bm.issueFollowOwner(entry);
            case STOP -> bm.issueStop(entry);
            case BUY_POTIONS -> bm.issueBuyPotions(entry);
            case TURN_IN_QUEST -> {
                // Quest turn-in automation is a later phase; log the intent so M1 stays honest.
                log.info("planner[{}] TURN_IN_QUEST npc {} requested but quest automation unavailable; idling",
                        bot.getName(), d.intParam("npcId", -1));
            }
            case REST_IN_TOWN -> bm.issueRestInTown(entry);
            case IDLE -> { /* intentional no-op */ }
        }
    }

    /** Test-only: expose currently registered bot ids. */
    static Set<Integer> registeredIdsForTest() {
        return Set.copyOf(REGISTERED.keySet());
    }
}
