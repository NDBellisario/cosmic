package server.bots.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotEntry;
import server.bots.llm.BotLlmConfig;
import server.bots.llm.OllamaClient;
import server.bots.llm.SituationBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code judge_completion}: the SLM call that decides which OPEN goals are now
 * complete — kept SEPARATE from task emission ({@link PlannerDecision} / emit_task)
 * so completion judgment and task choice are never overloaded into one generation.
 *
 * Each verdict carries a LITERAL evidence value copied from game state
 * (e.g. {@code "level=30"}), never prose, so the disagreement set (SLM belief vs
 * Tier-1/Opus review) is auditable. Uses constrained JSON
 * ({@link OllamaClient#generateJson}) — more reliable than free-form at 1-3B.
 */
public final class PlannerJudge {
    private static final Logger log = LoggerFactory.getLogger(PlannerJudge.class);

    /** One goal's completion verdict. {@code evidence} is a literal key=value from game state. */
    public record CompletionVerdict(String goalId, boolean complete, String evidence) {}

    static final String SYSTEM =
            "You are a strict completion judge for an autonomous MapleStory character. "
            + "You are given the character's OPEN goals and its current game state. "
            + "For EACH open goal, decide if it is already complete based ONLY on the state. "
            + "evidence MUST be a single literal value copied from the state that proves it, "
            + "formatted key=value (e.g. level=30, meso=1200000, map=104040000) — NEVER a sentence. "
            + "If no literal value proves completion, set complete=false. "
            + "Reply with ONE JSON object and nothing else: "
            + "{\"verdicts\":[{\"goal_id\":\"<id>\",\"complete\":true|false,\"evidence\":\"key=value\"}]}";

    private PlannerJudge() {}

    /** Build the judge prompt: the open goals (by id) + the current game state. */
    static String buildPrompt(List<GoalChecklist.Goal> openGoals, String situation) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Open goals (judge each by its id):\n");
        for (GoalChecklist.Goal g : openGoals) {
            sb.append("  ").append(g.id()).append(": ").append(g.text()).append('\n');
        }
        sb.append('\n').append(situation == null ? "" : situation).append('\n');
        sb.append("\nFor each open goal above, is it complete given the state? JSON only.");
        return sb.toString();
    }

    /** Runtime: ask the SLM which open goals are complete. Empty list on any failure. */
    static List<CompletionVerdict> judge(BotEntry entry, List<GoalChecklist.Goal> openGoals) {
        if (entry == null || openGoals == null || openGoals.isEmpty()) return List.of();
        String prompt = buildPrompt(openGoals, SituationBuilder.buildForPlanner(entry));
        Optional<String> raw = OllamaClient.generateJson(prompt, SYSTEM, BotLlmConfig.plannerMaxTokens);
        if (raw.isEmpty()) return List.of();
        List<CompletionVerdict> verdicts = parse(raw.get());
        String botName = entry.getBot() != null ? entry.getBot().getName() : "?";
        for (CompletionVerdict v : verdicts) {
            log.info("[LOOP] step=judge bot={} goal={} complete={} evidence=\"{}\"",
                    botName, v.goalId(), v.complete(), v.evidence());
        }
        return verdicts;
    }

    /** Parse {@code {"verdicts":[{"goal_id":..,"complete":..,"evidence":..},..]}} leniently. */
    static List<CompletionVerdict> parse(String raw) {
        List<CompletionVerdict> out = new ArrayList<>();
        if (raw == null) return out;
        for (String obj : objects(raw)) {
            String id = str(obj, "goal_id");
            if (id == null) id = str(obj, "goalId");
            if (id == null) continue;                 // not a verdict object
            String ev = str(obj, "evidence");
            out.add(new CompletionVerdict(id, bool(obj, "complete"), ev == null ? "" : ev));
        }
        return out;
    }

    // --- minimal hand-rolled JSON helpers (no Jackson, per project rule) ---

    /** The {...} verdict objects that are direct array elements of {"verdicts":[...]}. */
    private static List<String> objects(String s) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '"' && prev != '\\') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 1) start = i;            // element object inside the verdicts array
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 1 && start >= 0) { objs.add(s.substring(start, i + 1)); start = -1; }
            }
            prev = c;
        }
        if (objs.isEmpty()) objs.add(s);              // bare single object (no wrapper)
        return objs;
    }

    private static String str(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = obj.indexOf(':', k + key.length() + 2);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length() || obj.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '"' && prev != '\\') break;
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    private static boolean bool(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return false;
        int colon = obj.indexOf(':', k + key.length() + 2);
        if (colon < 0) return false;
        String rest = obj.substring(colon + 1).trim().toLowerCase();
        return rest.startsWith("true") || rest.startsWith("\"true\"") || rest.startsWith("1");
    }
}
