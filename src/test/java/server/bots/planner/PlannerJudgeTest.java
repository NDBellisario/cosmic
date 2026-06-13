package server.bots.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 gate for the SLM-loop refactor: the separated judge_completion call.
 * Feeds canned SLM JSON (the "answers") and asserts the parsed verdict struct,
 * including a distractor-numbers case to confirm literal evidence is read off the
 * right field rather than a same-valued HP/MP/map number.
 */
class PlannerJudgeTest {

    @Test
    void parsesMultipleVerdictsWithLiteralEvidence() {
        String raw = "{\"verdicts\":["
                + "{\"goal_id\":\"g1\",\"complete\":true,\"evidence\":\"level=30\"},"
                + "{\"goal_id\":\"g3\",\"complete\":false,\"evidence\":\"\"}"
                + "]}";
        List<PlannerJudge.CompletionVerdict> v = PlannerJudge.parse(raw);
        assertEquals(2, v.size());

        assertEquals("g1", v.get(0).goalId());
        assertTrue(v.get(0).complete());
        assertEquals("level=30", v.get(0).evidence());

        assertEquals("g3", v.get(1).goalId());
        assertFalse(v.get(1).complete());
        assertEquals("", v.get(1).evidence());
    }

    @Test
    void evidenceIsTheLiteralFieldNotADistractorNumber() {
        // The game state behind this verdict had HP=30, MP=30, and a map id containing 30,
        // all sharing the value 30 with the goal target. The judge's evidence must be the
        // literal key=value off the LEVEL field, and the parser must preserve it verbatim.
        String raw = "{\"verdicts\":[{\"goal_id\":\"g1\",\"complete\":true,\"evidence\":\"level=30\"}]}";
        PlannerJudge.CompletionVerdict v = PlannerJudge.parse(raw).get(0);
        assertEquals("level=30", v.evidence());
        assertTrue(v.evidence().startsWith("level="), "evidence must name the field, not a bare 30");
        assertFalse(v.evidence().contains("hp"));
        assertFalse(v.evidence().contains("mp"));
    }

    @Test
    void promptListsGoalsByIdAndIncludesLabeledStateAndLiteralRule() {
        List<GoalChecklist.Goal> goals = List.of(
                new GoalChecklist.Goal("g1", 1, "Reach level 30", false, "open", null, ""),
                new GoalChecklist.Goal("g2", 2, "Earn 1m mesos", false, "open", null, ""));
        // distractor-laden state: HP 30, MP 30, a map id containing 30, and the real Level 30
        String situation = "[Current situation]\n"
                + "Map: Subway (id 103000030)\n"
                + "Level 30, 5% to next\n"
                + "HP: 30%\n"
                + "Pots: 30 HP, 30 MP | Meso: 1.0m\n";
        String prompt = PlannerJudge.buildPrompt(goals, situation);

        assertTrue(prompt.contains("g1: Reach level 30"));
        assertTrue(prompt.contains("g2: Earn 1m mesos"));
        assertTrue(prompt.contains("Level 30"), "the disambiguating level field must be present + labeled");
        assertTrue(prompt.contains(situation.trim().split("\n")[0]));
        // the schema enforces literal key=value evidence, never prose
        assertTrue(PlannerJudge.SYSTEM.contains("key=value"));
        assertTrue(PlannerJudge.SYSTEM.toLowerCase().contains("never a sentence"));
    }

    @Test
    void acceptsCamelCaseGoalIdKey() {
        String raw = "{\"verdicts\":[{\"goalId\":\"g2\",\"complete\":true,\"evidence\":\"meso=1000000\"}]}";
        List<PlannerJudge.CompletionVerdict> v = PlannerJudge.parse(raw);
        assertEquals(1, v.size());
        assertEquals("g2", v.get(0).goalId());
        assertEquals("meso=1000000", v.get(0).evidence());
    }

    @Test
    void malformedOrEmptyYieldsNoVerdicts() {
        assertTrue(PlannerJudge.parse(null).isEmpty());
        assertTrue(PlannerJudge.parse("not json at all").isEmpty());
        assertTrue(PlannerJudge.parse("{}").isEmpty());
        assertTrue(PlannerJudge.parse("{\"verdicts\":[]}").isEmpty());
    }
}
