package server.bots.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 gate for the SLM-loop refactor: GOAL.md status schema parse/flip round-trip.
 * A mixed open/done GOAL.md goes through parse -> flip one goal -> reparse, asserting
 * only the target goal changed and every other line is byte-identical.
 */
class GoalChecklistTest {

    private static final String GOAL = String.join("\n",
            "# GOAL - TestBot",
            "",
            "## Checklist",
            "- [ ] g1: Reach level 30 <!-- status:open -->",
            "- [x] g2: Sell junk when bag fills <!-- status:done ts:2026-06-01T00:00Z evidence:\"sold=3\" -->",
            "- [ ] g3: Buy a new bow <!-- status:open -->",
            "",
            "## Notes",
            "keep grinding, stay alive",
            "");

    @Test
    void parsesIdStatusTsEvidence() {
        List<GoalChecklist.Goal> goals = GoalChecklist.parse(GOAL);
        assertEquals(3, goals.size());

        assertEquals("g1", goals.get(0).id());
        assertEquals("Reach level 30", goals.get(0).text());
        assertEquals("open", goals.get(0).status());
        assertFalse(goals.get(0).checked());

        assertEquals("g2", goals.get(1).id());
        assertEquals("done", goals.get(1).status());
        assertTrue(goals.get(1).checked());
        assertEquals("2026-06-01T00:00Z", goals.get(1).ts());
        assertEquals("sold=3", goals.get(1).evidence());
    }

    @Test
    void flipOneGoalLeavesEverythingElseByteIdentical(@TempDir Path dir) throws IOException {
        Path botDir = dir.resolve("TestBot");
        Files.createDirectories(botDir);
        Path goalPath = botDir.resolve("GOAL.md");
        Files.writeString(goalPath, GOAL, StandardCharsets.UTF_8);

        boolean wrote = GoalChecklist.markCompleteAt(goalPath, "TestBot", "g1", "level=30", "2026-06-13T18:00Z");
        assertTrue(wrote, "expected g1 to flip");

        String after = Files.readString(goalPath, StandardCharsets.UTF_8);
        List<GoalChecklist.Goal> goals = GoalChecklist.parse(after);

        // g1 is now done with the supplied ts + literal evidence
        GoalChecklist.Goal g1 = goals.get(0);
        assertTrue(g1.checked());
        assertEquals("done", g1.status());
        assertEquals("2026-06-13T18:00Z", g1.ts());
        assertEquals("level=30", g1.evidence());
        assertEquals("Reach level 30", g1.text(), "goal content must be preserved");

        // g2 (already done) and g3 (still open) untouched
        assertEquals("sold=3", goals.get(1).evidence());
        assertEquals("open", goals.get(2).status());

        // every line except the g1 line is byte-identical
        String[] before = GOAL.split("\n", -1);
        String[] now = after.split("\n", -1);
        assertEquals(before.length, now.length, "line count changed");
        for (int i = 0; i < before.length; i++) {
            if (before[i].contains("g1:")) continue; // the one intentionally-changed line
            assertEquals(before[i], now[i], "line " + i + " changed unexpectedly");
        }
    }

    @Test
    void markingAnAlreadyDoneGoalIsANoOp(@TempDir Path dir) throws IOException {
        Path botDir = dir.resolve("TestBot");
        Files.createDirectories(botDir);
        Path goalPath = botDir.resolve("GOAL.md");
        Files.writeString(goalPath, GOAL, StandardCharsets.UTF_8);

        // g2 is already done
        assertFalse(GoalChecklist.markCompleteAt(goalPath, "TestBot", "g2", "sold=99", "x"));
        // unknown id writes nothing
        assertFalse(GoalChecklist.markCompleteAt(goalPath, "TestBot", "g99", "x", "x"));
        // file is byte-identical to the original
        assertEquals(GOAL, Files.readString(goalPath, StandardCharsets.UTF_8));
    }
}
