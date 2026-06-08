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

    private GoalChecklist() {}

    /** One checklist line: its 1-based position among checklist items, its text, and state. */
    record Item(int index, String text, boolean checked) {}

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
