package server.bots.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hand-rolled planner JSON parser — the load-bearing piece that turns
 * messy SLM output into a typed decision. Small models wrap JSON in prose / code
 * fences and emit numbers or strings for params, so the parser must tolerate all of it.
 */
class PlannerDecisionTest {

    @Test
    void parsesCleanObject() {
        PlannerDecision d = PlannerDecision.parse(
                "{\"action\": \"GRIND\", \"reason\": \"goal says reach level 35\", \"params\": {}}");
        assertNotNull(d);
        assertEquals(PlannerAction.GRIND, d.action);
        assertEquals("goal says reach level 35", d.reason);
        assertTrue(d.params.isEmpty());
    }

    @Test
    void parsesNumericParam() {
        PlannerDecision d = PlannerDecision.parse(
                "{\"action\":\"GRIND_AT\",\"reason\":\"better mobs\",\"params\":{\"mapId\":104040000}}");
        assertNotNull(d);
        assertEquals(PlannerAction.GRIND_AT, d.action);
        assertEquals(104040000, d.intParam("mapId", -1));
    }

    @Test
    void parsesQuotedParam() {
        PlannerDecision d = PlannerDecision.parse(
                "{\"action\":\"TURN_IN_QUEST\",\"reason\":\"done\",\"params\":{\"npcId\":\"1012100\"}}");
        assertNotNull(d);
        assertEquals(PlannerAction.TURN_IN_QUEST, d.action);
        assertEquals(1012100, d.intParam("npcId", -1));
    }

    @Test
    void toleratesMarkdownFenceAndProse() {
        String raw = "Sure! Here is my decision:\n```json\n"
                + "{\"action\": \"FOLLOW_OWNER\", \"reason\": \"sticking with the party\", \"params\": {}}\n"
                + "```\nHope that helps.";
        PlannerDecision d = PlannerDecision.parse(raw);
        assertNotNull(d);
        assertEquals(PlannerAction.FOLLOW_OWNER, d.action);
        assertEquals("sticking with the party", d.reason);
    }

    @Test
    void caseInsensitiveAction() {
        PlannerDecision d = PlannerDecision.parse("{\"action\":\"rest_in_town\",\"reason\":\"low pots\"}");
        assertNotNull(d);
        assertEquals(PlannerAction.REST_IN_TOWN, d.action);
    }

    @Test
    void missingReasonDefaultsEmpty() {
        PlannerDecision d = PlannerDecision.parse("{\"action\":\"STOP\"}");
        assertNotNull(d);
        assertEquals(PlannerAction.STOP, d.action);
        assertEquals("", d.reason);
    }

    @Test
    void unknownActionRejected() {
        assertNull(PlannerDecision.parse("{\"action\":\"DANCE\",\"reason\":\"vibes\"}"));
    }

    @Test
    void garbageRejected() {
        assertNull(PlannerDecision.parse("I don't know what to do honestly"));
        assertNull(PlannerDecision.parse(""));
        assertNull(PlannerDecision.parse(null));
    }

    @Test
    void intParamFallsBackWhenAbsent() {
        PlannerDecision d = PlannerDecision.parse("{\"action\":\"GRIND_AT\",\"params\":{}}");
        assertNotNull(d);
        assertEquals(-1, d.intParam("mapId", -1));
    }

    @Test
    void extractsFirstBalancedObjectIgnoringStringBraces() {
        String s = "prefix {\"action\":\"GRIND\",\"reason\":\"go to {the spot}\"} suffix {ignored}";
        String obj = PlannerDecision.extractFirstObject(s);
        assertNotNull(obj);
        assertEquals("{\"action\":\"GRIND\",\"reason\":\"go to {the spot}\"}", obj);
    }
}
