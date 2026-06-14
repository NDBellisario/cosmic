package server.bots.planner;

import org.junit.jupiter.api.Test;
import server.bots.llm.AnthropicClient;
import server.bots.llm.BotLlmConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE end-to-end harness for the Tier-1 orchestrator. Exercises the real authoring
 * path WITHOUT a running game: world-index grounding ({@link Tier1Orchestrator#gatherGrounding})
 * → real Anthropic call ({@link AnthropicClient}) with the production system prompt
 * → format + grounding validation of the authored GOAL.md.
 *
 * <p>It does NOT construct a live {@code BotEntry}/{@code SituationBuilder} snapshot — a
 * representative situation block is supplied inline. Everything else (the Sonnet call, the
 * world-index subprocess, the GOAL.md parse contract) is the real thing.
 *
 * <p><b>Gated so it never runs (and never costs money) in a normal build.</b> Requires
 * BOTH {@code -Dtier1.live=true} and {@code ANTHROPIC_API_KEY} set; otherwise it is skipped
 * via JUnit assumptions. Run it explicitly:
 * <pre>./mvnw test -Dtest=Tier1LiveHarnessTest -Dtier1.live=true</pre>
 * The authored GOAL.md is written to {@code target/tier1-authored-GOAL.md} for inspection.
 */
class Tier1LiveHarnessTest {

    private static final Pattern MAP_ID = Pattern.compile("\\b\\d{9}\\b");

    @Test
    void authorsGroundedGoalFromRealApi() throws Exception {
        assumeTrue(Boolean.getBoolean("tier1.live"),
                "live test — pass -Dtier1.live=true to run (makes a real, paid Anthropic call)");
        assumeTrue(AnthropicClient.isConfigured(),
                "no API key (set MAPLE_AGENT_ANTHROPIC_API_KEY) — cannot make a live call");

        int level = 35;
        int jobId = 220;   // IL Wizard (mage branch)

        // --- real world-index grounding (subprocess to ../world-index/query.py) ---
        String grounding = Tier1Orchestrator.gatherGrounding(level, jobId);
        System.out.println("=== world-index grounding ===\n" + grounding);
        assertFalse(grounding.isBlank(),
                "world-index returned nothing — is " + BotLlmConfig.worldIndexDir + "/query.py + world.sqlite present?");

        // --- representative inputs (stand in for SOUL.md / a batch-complete GOAL.md / SituationBuilder) ---
        String soul = "Vesper is a calm, studious Ice/Lightning mage. Methodical, prefers efficient grinding, "
                + "saves mesos, avoids dying.";
        String currentGoal = "# GOAL — Vesper (today)\n\n## Checklist\n"
                + "- [x] g1: Reach level 35 <!-- status:done ts:2026-06-14T08:00:00Z evidence:\"level=35\" -->\n"
                + "- [x] g2: Sell junk drops whenever the bag fills <!-- status:done ts:2026-06-14T08:00:00Z evidence:\"sold=4\" -->\n";
        String situation = "[Current situation]\nMap: Henesys (id 100000000)\nStatus: standing around in town\n"
                + "Level 35, 18% to next\nHP: 96%\nPots: 40 HP, 25 MP | Meso: 120k | Bag(ETC): 30/96";

        String prompt = Tier1Orchestrator.buildPrompt(
                "Vesper", "all 2 goals complete — author the next stretch",
                soul, currentGoal, situation, grounding);

        // --- the real Anthropic call (production system prompt + token budget) ---
        long t0 = System.currentTimeMillis();
        Optional<String> raw = AnthropicClient.complete(prompt, Tier1Orchestrator.SYSTEM, BotLlmConfig.tier1MaxTokens);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("=== Anthropic call: " + (raw.isPresent() ? "OK" : "EMPTY") + " in " + ms + "ms, model="
                + BotLlmConfig.tier1Model + " ===");
        assertTrue(raw.isPresent(), "no response from Anthropic (" + ms + "ms) — check key/model/endpoint");

        String goalMd = Tier1Orchestrator.stripFences(raw.get());
        Path artifact = Path.of("target", "tier1-authored-GOAL.md");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, goalMd, StandardCharsets.UTF_8);
        System.out.println("=== authored GOAL.md (also at " + artifact.toAbsolutePath() + ") ===\n" + goalMd);

        // --- contract: parses into real goal lines ---
        List<GoalChecklist.Goal> goals = GoalChecklist.parse(goalMd);
        assertTrue(goals.size() >= 3,
                "expected >= 3 parseable goal lines, got " + goals.size() + ":\n" + goalMd);
        for (GoalChecklist.Goal g : goals) {
            assertTrue(g.id() != null && g.id().matches("g\\d+"),
                    "goal id should look like gN, was: " + (g.id()));
            assertTrue("open".equals(g.status()),
                    "freshly authored goal should be open, was: " + g.status() + " (" + g.id() + ")");
        }

        // --- contract: the literal status comment the system prompt demands ---
        long openMarkers = goalMd.lines().filter(l -> l.contains("<!-- status:open -->")).count();
        assertTrue(openMarkers >= 3,
                "expected >= 3 lines with the literal <!-- status:open --> marker, found " + openMarkers);

        // --- grounding: every 9-digit map id in the authored file must come from the grounding data ---
        Matcher m = MAP_ID.matcher(goalMd);
        while (m.find()) {
            String mapId = m.group();
            assertTrue(grounding.contains(mapId),
                    "authored GOAL.md cites map id " + mapId + " that is NOT in the world-index grounding "
                            + "(hallucination): \n" + goalMd);
        }

        System.out.println("=== PASS: " + goals.size() + " open goals, " + openMarkers
                + " status markers, no hallucinated map ids ===");
    }
}
