package server.bots.planner;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotEntry;
import server.bots.BotManager;
import server.bots.llm.BotLlmConfig;
import server.bots.llm.OllamaClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ---- Brain snapshot (last full cycle per bot) ---------------------------
    /**
     * Full snapshot of the most-recent planner decision cycle for one bot.
     * Immutable record; safe to hand to the HTTP thread without synchronization.
     *
     * @param epochMs        when this cycle ran (ms since epoch)
     * @param situation      the SituationBuilder text included in the user prompt
     * @param input          the full user-prompt string fed to the SLM
     * @param system         the system-prompt string fed to the SLM
     * @param rawOutput      the verbatim SLM response
     * @param action         parsed action name, e.g. "GRIND"
     * @param reason         parsed reason string
     * @param params         parsed params as "key=value,..." (empty string if none)
     * @param nextRunEpochMs estimated epoch ms when the next cycle will fire
     */
    public record BrainSnapshot(
            long epochMs,
            String situation,
            String input,
            String system,
            String rawOutput,
            String action,
            String reason,
            String params,
            long nextRunEpochMs
    ) {}

    /** botCharId → most-recent full cycle snapshot. Planner thread writes; HTTP thread reads. */
    private static final ConcurrentHashMap<Integer, BrainSnapshot> BRAIN_SNAPSHOTS = new ConcurrentHashMap<>();

    // ---- Goal-completion tracking (two-tick confirm + stuck-detector) -------
    /** Per-bot harness memory for the judge step. Only touched on the planner executor. */
    private static final ConcurrentHashMap<Integer, CompletionTracker> TRACKERS = new ConcurrentHashMap<>();

    /**
     * Tracks consecutive "complete" judgments per goal so the harness only flips a
     * status flag after {@link BotLlmConfig#plannerConfirmTicks} consecutive ticks
     * (two-tick confirmation), and counts ticks-without-progress for the stuck-detector.
     * Deterministic and unit-tested in CompletionTrackerTest.
     */
    static final class CompletionTracker {
        private final Map<String, Integer> consecutive = new HashMap<>();
        private int ticksSinceProgress = 0;

        /** Record this tick's verdict for one goal. Returns true iff it should flip NOW
         *  (i.e. just hit the confirm threshold). A non-complete verdict resets its run. */
        boolean confirm(String goalId, boolean complete) {
            if (!complete) {
                consecutive.put(goalId, 0);
                return false;
            }
            int n = consecutive.merge(goalId, 1, Integer::sum);
            if (n >= Math.max(1, BotLlmConfig.plannerConfirmTicks)) {
                consecutive.put(goalId, 0);  // reset so a still-open goal doesn't re-fire
                return true;
            }
            return false;
        }

        /** A flip happened this tick — reset the stuck counter. */
        void onProgress() { ticksSinceProgress = 0; }

        /** No flip this tick — advance and return ticks-without-progress. */
        int tickNoProgress() { return ++ticksSinceProgress; }
    }

    /**
     * Returns the most-recent {@link BrainSnapshot} for {@code botId}, or {@code null} if
     * no cycle has completed yet. Safe to call from any thread.
     */
    public static BrainSnapshot brainSnapshot(int botId) {
        return BRAIN_SNAPSHOTS.get(botId);
    }

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
        BRAIN_SNAPSHOTS.remove(id);
        TRACKERS.remove(id);
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
            long hb = Math.max(10_000L, BotLlmConfig.plannerHeartbeatMs);
            EXEC.scheduleWithFixedDelay(BotAutonomousPlanner::heartbeat, hb, hb, TimeUnit.MILLISECONDS);
            started = true;
            log.info("planner: sweep loop started (interval {}ms, heartbeat {}ms)", period, hb);
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
     * Heartbeat: every plannerHeartbeatMs, force a fresh decision for each OWNERLESS
     * bot even if it is mid-action (hunting). Keeps the autonomous loop and the SLM
     * alive and lets the bot periodically reassess its plan. Owner-driven bots are
     * left to the idle-gated sweep so owner commands are never overridden.
     */
    private static void heartbeat() {
        if (!BotLlmConfig.plannerEnabled) return;
        for (BotEntry entry : REGISTERED.values()) {
            try {
                if (entry.isOwnerless()) {
                    submit(entry, "heartbeat", true);
                }
            } catch (Throwable t) {
                log.warn("planner: heartbeat submit failed: {}", t.toString());
            }
        }
    }

    /**
     * Gate + dispatch one decision onto the planner executor. Skips bots that are
     * not idle (owner commands and any active mode win), already have a decision in
     * flight, or when the global concurrency cap is saturated.
     */
    private static void submit(BotEntry entry, String eventReason) {
        submit(entry, eventReason, false);
    }

    private static void submit(BotEntry entry, String eventReason, boolean force) {
        if (!BotLlmConfig.plannerEnabled) return;
        Character bot = entry.getBot();
        if (bot == null || bot.getMap() == null) return;

        // Owner commands and any active mode take priority — planner only fills idle.
        // A forced heartbeat re-evaluation (ownerless bots) bypasses this so the bot
        // periodically reassesses even mid-hunt.
        if (!force && !entry.isIdleForPlanner()) return;

        int id = bot.getId();
        AtomicBoolean inflight = INFLIGHT.computeIfAbsent(id, k -> new AtomicBoolean(false));
        if (!inflight.compareAndSet(false, true)) return;

        if (!GATE.tryAcquire()) {
            inflight.set(false);
            return;
        }

        EXEC.submit(() -> {
            try {
                decide(entry, eventReason, force);
            } catch (Throwable t) {
                log.warn("planner: decide failed for {}: {}", bot.getName(), t.toString());
            } finally {
                GATE.release();
                inflight.set(false);
            }
        });
    }

    private static void decide(BotEntry entry, String eventReason) {
        decide(entry, eventReason, false);
    }

    /** Build prompt → call SLM → parse → dispatch. Off-tick. */
    private static void decide(BotEntry entry, String eventReason, boolean force) {
        Character bot = entry.getBot();
        String botName = bot.getName();

        // step 1 — read GOAL.md + state.
        String soul = ContextFileLoader.loadSoul(botName);
        String goal = ContextFileLoader.loadGoal(botName);
        log.info("[LOOP] step=read bot={} openGoals={}", botName,
                (int) GoalChecklist.parse(goal).stream().filter(g -> "open".equals(g.status())).count());

        // steps 2-3 — judge which OPEN goals are complete; flip two-tick-confirmed ones.
        // Re-read the goal afterward so the emit prompt reflects any flip from this tick.
        judgeAndMark(entry, botName, goal);
        goal = ContextFileLoader.loadGoal(botName);

        // step 4 — emit a task for the current state.
        String system = PlannerPromptBuilder.buildSystem(entry, soul);
        // Capture the situation text separately (it's embedded inside prompt, but surface it
        // explicitly so the brain view can show it without re-parsing the full prompt).
        String situation = server.bots.llm.SituationBuilder.buildForPlanner(entry);
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
        // A forced heartbeat re-evaluation applies regardless (it has no owner to defer to).
        if (!force && !entry.isIdleForPlanner()) {
            if (BotLlmConfig.debugLog) {
                log.info("planner[{}] decided {} but bot no longer idle — owner command wins",
                        botName, decision.action);
            }
            return;
        }

        log.info("planner[{}] decision={} reason=\"{}\" ({}ms)",
                botName, decision.action, decision.reason, elapsed);
        log.info("[LOOP] step=emit bot={} action={}", botName, decision.action);
        recordDecision(bot.getId(), decision.action.name(), decision.reason);

        // Capture the full brain snapshot for the debug panel. nextRunEpochMs is the
        // estimated time of the next cycle: heartbeat period for ownerless bots, sweep
        // period otherwise (same intervals used in ensureStarted).
        long nextInterval = entry.isOwnerless()
                ? Math.max(10_000L, BotLlmConfig.plannerHeartbeatMs)
                : Math.max(1000L, BotLlmConfig.plannerIntervalMs);
        String paramsStr = buildParamsString(decision);
        BRAIN_SNAPSHOTS.put(bot.getId(), new BrainSnapshot(
                System.currentTimeMillis(),
                situation != null ? situation : "",
                prompt,
                system != null ? system : "",
                raw.get(),
                decision.action.name(),
                decision.reason != null ? decision.reason : "",
                paramsStr,
                System.currentTimeMillis() + nextInterval
        ));

        apply(entry, decision);
        // NOTE: completion is no longer read off the emit call's "done" field — the
        // separate judge_completion step (judgeAndMark, above) owns status flips now.
    }

    /**
     * Steps 2-3 of the loop: ask the SLM which OPEN goals are complete, and flip the
     * GOAL.md status flag for any goal confirmed complete on {@code plannerConfirmTicks}
     * consecutive ticks (two-tick confirmation). The harness owns sequencing and the
     * single safe edit; the SLM only judges. Emits {@code [LOOP]} markers throughout.
     */
    private static void judgeAndMark(BotEntry entry, String botName, String goal) {
        List<GoalChecklist.Goal> open = GoalChecklist.parse(goal).stream()
                .filter(g -> "open".equals(g.status()))
                .toList();
        if (open.isEmpty()) {
            return;  // nothing open to judge (all-done -> Tier-1 escalation is Phase 4)
        }
        CompletionTracker tracker = TRACKERS.computeIfAbsent(entry.getBot().getId(), k -> new CompletionTracker());

        List<PlannerJudge.CompletionVerdict> verdicts = PlannerJudge.judge(entry, open);
        Map<String, PlannerJudge.CompletionVerdict> byId = new HashMap<>();
        for (PlannerJudge.CompletionVerdict v : verdicts) byId.put(v.goalId(), v);

        boolean flipped = false;
        String nowIso = Instant.now().toString();
        for (GoalChecklist.Goal g : open) {
            PlannerJudge.CompletionVerdict v = byId.get(g.id());
            boolean complete = v != null && v.complete();
            if (tracker.confirm(g.id(), complete)) {                 // two-tick confirmed
                String ev = v != null ? v.evidence() : "";
                if (GoalChecklist.markComplete(botName, g.id(), ev, nowIso)) {
                    log.info("[LOOP] step=mark bot={} goal={} complete=true evidence=\"{}\"", botName, g.id(), ev);
                    flipped = true;
                }
            }
        }

        if (flipped) {
            tracker.onProgress();
        } else if (tracker.tickNoProgress() >= Math.max(1, BotLlmConfig.plannerStuckTicks)) {
            // Stuck: completion set unchanged for K ticks while goals remain open.
            log.info("[LOOP] step=stuck bot={} signal=planner open={} reason=\"no completion progress in {} ticks\"",
                    botName, open.size(), BotLlmConfig.plannerStuckTicks);
            tracker.onProgress();  // re-arm: signal once every K ticks, not every tick
        }
    }

    /** Flatten a decision's params map to "key=val, ..." for the brain snapshot. */
    private static String buildParamsString(PlannerDecision d) {
        if (d.params == null || d.params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : d.params.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
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
            case SELL -> bm.issueSellJunk(entry);
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
