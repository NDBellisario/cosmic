package server.bots.llm;

import client.Character;
import server.bots.BotEntry;
import server.bots.planner.ContextFileLoader;
import server.bots.planner.GoalChecklist;

import java.util.List;

public final class PromptBuilder {
    private PromptBuilder() {}

    /** Max SOUL chars to weave into the chat system prompt. Chat runs on a tight
     *  numCtx (2048) budget, so we cap the persona to avoid crowding the conversation. */
    private static final int SOUL_CHAR_CAP = 550;

    /** Max chars of the bot's current GOAL.md objectives to surface in chat, so it can
     *  answer "what are you up to?" from its real plan without reciting a checklist. */
    private static final int GOAL_CHAR_CAP = 240;

    public static String buildSystem(BotEntry entry, SenderRelation relation, String senderName) {
        Character bot = entry.getBot();
        String botName = bot != null ? bot.getName() : "bot";
        String job = bot != null ? bot.getJob().toString().toLowerCase().replace('_', ' ') : "adventurer";
        int lvl = bot != null ? bot.getLevel() : 1;

        String relationLine = switch (relation) {
            case OWNER -> "You are talking to your close gaming friend and party leader " + senderName + ". Be friendly and helpful.";
            case PARTY -> "You are talking to " + senderName + ", your party member. Be casual.";
            case STRANGER -> "You are talking to " + senderName + ", a stranger you just met in the game.";
        };

        String personaLine = (bot != null) ? buildPersonaLine(botName) : "";
        String goalLine = (bot != null) ? buildGoalLine(botName) : "";

        return "Your IGN is " + botName + ". "
                + "You are a real human MapleStory player, level " + lvl + " " + job + ". "
                + personaLine
                + goalLine
                + "Talk like mmo chatter: lowercase, usually 1 (at most 2) short sentences, abbreviate when natural, text only, internet slang when it fits, no emoji, no speaker label. "
                + "Let your personality and what you're doing show through. "
                + "Answer only the latest message directly. If unsure, say so briefly instead of guessing. "
                + "Never say you are an ai, assistant, chatbot, or bot. "
                + relationLine;
    }

    /** Builds a "what you're currently doing" line from the bot's OPEN GOAL.md goals,
     *  or "" if none. Lets the bot talk about its real objectives in chat. */
    private static String buildGoalLine(String botName) {
        String goalMd;
        try {
            goalMd = ContextFileLoader.loadGoal(botName);
        } catch (RuntimeException e) {
            return "";
        }
        if (goalMd == null || goalMd.isBlank()) {
            return "";
        }
        List<String> open = GoalChecklist.openGoalTexts(goalMd);
        if (open.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("What you're working on right now: ");
        for (int i = 0; i < open.size() && sb.length() < GOAL_CHAR_CAP; i++) {
            if (i > 0) sb.append("; ");
            sb.append(open.get(i).strip());
        }
        sb.append(". Mention this naturally if asked what you're up to — don't recite the list. ");
        return sb.toString();
    }

    /** Builds a trimmed "who you are" line from the bot's SOUL.md, or "" if absent.
     *  SOUL describes WHO the character is; the style rules govern HOW they type. */
    private static String buildPersonaLine(String botName) {
        String soul;
        try {
            soul = ContextFileLoader.loadSoul(botName);
        } catch (RuntimeException e) {
            return "";
        }
        if (soul == null || soul.isBlank()) {
            return "";
        }
        String trimmed = soul.strip();
        if (trimmed.length() > SOUL_CHAR_CAP) {
            // Prefer cutting at a paragraph break within the cap, else hard-cut.
            int cut = trimmed.lastIndexOf("\n\n", SOUL_CHAR_CAP);
            trimmed = (cut > 0 ? trimmed.substring(0, cut) : trimmed.substring(0, SOUL_CHAR_CAP)).strip();
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return "Stay in character as described here: " + trimmed + " ";
    }

    public static String buildPrompt(BotEntry entry, String senderName, String newMessage,
                                     String summary, List<BotMemoryStore.Turn> recent) {
        StringBuilder sb = new StringBuilder(512);
        if (summary != null && !summary.isBlank()) {
            sb.append("What you remember: ").append(summary).append("\n\n");
        }
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        if (recent != null && !recent.isEmpty()) {
            sb.append("Recent chat (older lines matter less):\n");
            String botName = entry.getBot() != null ? entry.getBot().getName() : "bot";
            long now = System.currentTimeMillis();
            for (BotMemoryStore.Turn t : recent) {
                String age = SituationBuilder.ago(now - t.ts());
                sb.append('[').append(age).append(" ago] ")
                        .append(t.sender()).append(": ").append(t.msg()).append('\n');
                sb.append(botName).append(": ").append(t.reply()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Reply to the newest message only. Treat older chat as background, not the topic.\n");
        sb.append(senderName).append(": ").append(newMessage).append('\n');
        sb.append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }
}
