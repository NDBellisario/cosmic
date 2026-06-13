package server.bots.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.llm.BotLlmConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic editor for a bot's GOAL.md *checklist*. Flips a markdown task box
 * ("- [ ]" → "- [x]") by 1-based item index, preserving every other byte of the
 * file. The SLM never rewrites goal text — it only reports which checklist items
 * it has completed (a {@code "done":[...]} array of indices in its decision), and
 * this class applies that single, safe edit. Keeps the human-authored goals/notes
 * intact while letting the bot tick off progress.
 */
public final class GoalChecklist {
    private static final Logger log = LoggerFactory.getLogger(GoalChecklist.class);

    // A markdown task-list line: optional indent, "-"/"*", "[", a box char, "]", rest.
    private static final Pattern ITEM = Pattern.compile("^(\\s*[-*]\\s*\\[)([ xX])(\\].*)$");

    // A *goal* line carries a stable id + an optional status comment the harness owns:
    //   - [ ] g1: reach level 30 <!-- status:open -->
    //   - [x] g2: sell junk       <!-- status:done ts:2026-06-13T18:00Z evidence:"sold=3" -->
    // Groups: 1=prefix "- [", 2=box char, 3="] ", 4=id, 5=text,
    //         6=status(open|done), 7=ts, 8=evidence  (6-8 absent on un-annotated lines).
    private static final Pattern GOAL_LINE = Pattern.compile(
            "^(\\s*[-*]\\s*\\[)([ xX])(\\]\\s+)([A-Za-z0-9_]+):\\s*(.*?)\\s*"
                    + "(?:<!--\\s*status:(open|done)(?:\\s+ts:(\\S+))?(?:\\s+evidence:\"([^\"]*)\")?\\s*-->)?\\s*$");

    private GoalChecklist() {}

    /** One checklist line: its 1-based position among checklist items, its text, and state. */
    record Item(int index, String text, boolean checked) {}

    /**
     * A goal with the harness-owned status flag. {@code id} is the stable Tier-1
     * identifier (e.g. "g1"); {@code status} is "open" or "done"; {@code ts} and
     * {@code evidence} are populated when the harness flips it done (else null/"").
     */
    record Goal(String id, int index, String text, boolean checked,
                String status, String ts, String evidence) {}

    /** Parse the goal lines (id + status metadata) out of a GOAL.md body, in order. */
    static List<Goal> parse(String goal) {
        List<Goal> out = new ArrayList<>();
        if (goal == null) return out;
        int idx = 0;
        for (String line : goal.split("\n", -1)) {
            Matcher m = GOAL_LINE.matcher(line);
            if (!m.matches()) continue;
            idx++;
            boolean checked = !m.group(2).equals(" ");
            String status = m.group(6) != null ? m.group(6) : (checked ? "done" : "open");
            out.add(new Goal(
                    m.group(4),               // id
                    idx,
                    m.group(5).trim(),        // text
                    checked,
                    status,
                    m.group(7),               // ts (may be null)
                    m.group(8) != null ? m.group(8) : ""));  // evidence
        }
        return out;
    }

    /**
     * Atomically mark goal {@code goalId} complete in {@code <plannerDataDir>/<botName>/GOAL.md}:
     * flip its box to {@code [x]} and set its status comment to
     * {@code <!-- status:done ts:<ts> evidence:"<evidence>" -->}, preserving every
     * other byte of the file. Idempotent: a goal already done is left untouched.
     * Returns true iff a write happened. {@code evidence} must be a literal game-state
     * value (e.g. "level=30"), never prose.
     */
    static boolean markComplete(String botName, String goalId, String evidence, String ts) {
        if (botName == null || botName.isBlank()) return false;
        return markCompleteAt(Path.of(BotLlmConfig.plannerDataDir, botName, "GOAL.md"),
                botName, goalId, evidence, ts);
    }

    /** Path-injectable core of {@link #markComplete} (so tests need no config). */
    static boolean markCompleteAt(Path path, String botName, String goalId, String evidence, String ts) {
        if (goalId == null || goalId.isBlank() || !Files.isRegularFile(path)) return false;
        // Keep the HTML comment well-formed: evidence carries no quotes/newlines.
        String ev = (evidence == null ? "" : evidence).replace("\"", "'").replace("\n", " ");
        String stamp = (ts == null ? "" : ts);
        try {
            String[] lines = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
            boolean changed = false;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = GOAL_LINE.matcher(lines[i]);
                if (!m.matches() || !goalId.equals(m.group(4))) continue;
                if (!m.group(2).equals(" ")) return false;  // already done -> idempotent no-op
                lines[i] = m.group(1) + "x" + m.group(3) + m.group(4) + ": " + m.group(5).trim()
                        + " <!-- status:done ts:" + stamp + " evidence:\"" + ev + "\" -->";
                changed = true;
                log.info("planner[{}] goal {} complete evidence=\"{}\"", botName, goalId, ev);
                break;
            }
            if (!changed) return false;
            Path tmp = path.resolveSibling("GOAL.md.tmp");
            Files.writeString(tmp, String.join("\n", lines), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            ContextFileLoader.invalidate(botName, "GOAL.md");
            return true;
        } catch (IOException e) {
            log.warn("planner[{}] goal-complete write failed: {}", botName, e.toString());
            return false;
        }
    }

    /** Parse the checklist items (in order) out of a GOAL.md body. */
    static List<Item> items(String goal) {
        List<Item> out = new ArrayList<>();
        if (goal == null) return out;
        int idx = 0;
        for (String line : goal.split("\n", -1)) {
            Matcher m = ITEM.matcher(line);
            if (m.matches()) {
                idx++;
                boolean checked = !m.group(2).equals(" ");
                String text = m.group(3).substring(1).trim(); // strip leading "]"
                out.add(new Item(idx, text, checked));
            }
        }
        return out;
    }

    /**
     * Mark the given 1-based checklist items done in
     * {@code <plannerDataDir>/<botName>/GOAL.md}. Already-checked items are left
     * alone. Returns the number of boxes newly checked (0 = nothing written).
     */
    static int markDone(String botName, int[] indices) {
        if (botName == null || botName.isBlank() || indices == null || indices.length == 0) return 0;
        Path path = Path.of(BotLlmConfig.plannerDataDir, botName, "GOAL.md");
        if (!Files.isRegularFile(path)) return 0;

        Set<Integer> want = new HashSet<>();
        for (int i : indices) want.add(i);

        try {
            String[] lines = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
            int item = 0;
            int changed = 0;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ITEM.matcher(lines[i]);
                if (!m.matches()) continue;
                item++;
                if (want.contains(item) && m.group(2).equals(" ")) {
                    lines[i] = m.group(1) + "x" + m.group(3);
                    changed++;
                    log.info("planner[{}] checklist item {} done: {}", botName, item, lines[i].trim());
                }
            }
            if (changed == 0) return 0;

            // Atomic-ish write: temp file + move, so a reader never sees a half-written GOAL.md.
            Path tmp = path.resolveSibling("GOAL.md.tmp");
            Files.writeString(tmp, String.join("\n", lines), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            ContextFileLoader.invalidate(botName, "GOAL.md");
            return changed;
        } catch (IOException e) {
            log.warn("planner[{}] checklist write failed: {}", botName, e.toString());
            return 0;
        }
    }
}
