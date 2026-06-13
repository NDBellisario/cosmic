package server.bots.planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.bots.llm.BotLlmConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 gate (deterministic half): two-tick confirmation + stuck counter logic.
 * The "flag flips exactly once after two consecutive done-judgments" behavior is
 * verified live; this pins the underlying state machine.
 */
class CompletionTrackerTest {

    @BeforeEach
    void resetConfirmThreshold() {
        BotLlmConfig.plannerConfirmTicks = 2;
    }

    @Test
    void flipsOnlyOnTheSecondConsecutiveComplete() {
        var t = new BotAutonomousPlanner.CompletionTracker();
        assertFalse(t.confirm("g1", true), "1st complete must not flip");
        assertTrue(t.confirm("g1", true), "2nd consecutive complete flips");
        // run resets after firing
        assertFalse(t.confirm("g1", true));
        assertTrue(t.confirm("g1", true));
    }

    @Test
    void anOpenJudgmentResetsTheRun() {
        var t = new BotAutonomousPlanner.CompletionTracker();
        assertFalse(t.confirm("g1", true));   // 1
        assertFalse(t.confirm("g1", false));  // judged open again -> reset
        assertFalse(t.confirm("g1", true));   // back to 1
        assertTrue(t.confirm("g1", true));    // 2 -> flip
    }

    @Test
    void goalsAreTrackedIndependently() {
        var t = new BotAutonomousPlanner.CompletionTracker();
        assertFalse(t.confirm("g1", true));
        assertFalse(t.confirm("g2", true));   // g2's run is separate from g1's
        assertTrue(t.confirm("g1", true));    // g1 -> flip
        assertFalse(t.confirm("g2", false));  // g2 reset, independent
        assertFalse(t.confirm("g2", true));
        assertTrue(t.confirm("g2", true));
    }

    @Test
    void confirmThresholdIsConfigurable() {
        BotLlmConfig.plannerConfirmTicks = 3;
        var t = new BotAutonomousPlanner.CompletionTracker();
        assertFalse(t.confirm("g1", true));
        assertFalse(t.confirm("g1", true));
        assertTrue(t.confirm("g1", true), "flips on the 3rd with threshold=3");
    }

    @Test
    void stuckCounterAdvancesAndResetsOnProgress() {
        var t = new BotAutonomousPlanner.CompletionTracker();
        assertEquals(1, t.tickNoProgress());
        assertEquals(2, t.tickNoProgress());
        assertEquals(3, t.tickNoProgress());
        t.onProgress();
        assertEquals(1, t.tickNoProgress(), "a flip resets ticks-without-progress");
    }
}
