package server.bots.planner;

import client.Character;
import server.bots.BotEntry;
import server.bots.llm.SituationBuilder;

/**
 * Builds the system + user prompts for one autonomous planner decision.
 *
 *   System = SOUL.md personality + the planner role + the action vocabulary +
 *            the strict JSON output contract.
 *   User   = GOAL.md + a SituationBuilder snapshot (map / status / inventory /
 *            mobs) + optional recent-events line.
 *
 * The contract is deliberately rigid: the model must answer with exactly one
 * JSON object. {@link PlannerDecision} parses it leniently, but a tight prompt
 * keeps small models on-rails.
 */
final class PlannerPromptBuilder {
    private PlannerPromptBuilder() {}

    static String buildSystem(BotEntry entry, String soul) {
        Character bot = entry.getBot();
        String botName = bot != null ? bot.getName() : "bot";
        String job = bot != null ? bot.getJob().toString().toLowerCase().replace('_', ' ') : "adventurer";

        // Prefer the globally-selected template from the prompt library (panel-editable).
        // Falls back to the built-in prompt below when no active template is configured.
        String fromLibrary = PlannerPromptLibrary.render(botName, job, soul);
        if (fromLibrary != null) {
            return fromLibrary;
        }
        return buildBuiltinSystem(botName, job, soul);
    }

    /** The original hardcoded system prompt; the fallback when no library template is active. */
    private static String buildBuiltinSystem(String botName, String job, String soul) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("You are ").append(botName)
                .append(", an autonomous MapleStory character (a ").append(job)
                .append("). You decide your own next action like an independent player, not a tool.\n");

        if (soul != null && !soul.isBlank()) {
            sb.append("\nYour personality and preferences (SOUL):\n")
                    .append(soul.trim()).append('\n');
        }

        sb.append("\nYou must choose exactly ONE action from this list:\n")
                .append("- GRIND: hunt mobs where you are now.\n")
                .append("- GRIND_AT: travel to a map then hunt there. params: {\"mapId\": <int>}\n")
                .append("- TRAVEL_TO: move to a map without grinding. params: {\"mapId\": <int>}\n")
                .append("- FOLLOW_OWNER: follow your owner around.\n")
                .append("- STOP: stop moving and stand still.\n")
                .append("- BUY_POTIONS: go restock HP/MP potions (use when pots are low).\n")
                .append("- TURN_IN_QUEST: hand in a quest. params: {\"npcId\": <int>}\n")
                .append("- REST_IN_TOWN: head to town and idle safely.\n")
                .append("- IDLE: do nothing this round.\n");

        sb.append("\nReply with ONE JSON object and nothing else:\n")
                .append("{\"action\": \"GRIND\", \"reason\": \"short why\", \"params\": {}}\n")
                .append("Rules: pick the single action that best advances your GOAL given the situation. ")
                .append("Keep \"reason\" to one short sentence. ")
                .append("Use params only for GRIND_AT, TRAVEL_TO, TURN_IN_QUEST. No prose, no markdown, JSON only.");
        return sb.toString();
    }

    static String buildPrompt(BotEntry entry, String goal, String recentEvents) {
        StringBuilder sb = new StringBuilder(640);

        if (goal != null && !goal.isBlank()) {
            sb.append("Your goals for today (GOAL):\n").append(goal.trim()).append("\n\n");
        } else {
            sb.append("You have no written goals today; use your judgment.\n\n");
        }

        String situation = SituationBuilder.buildForPlanner(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }

        if (recentEvents != null && !recentEvents.isBlank()) {
            sb.append("Recent events: ").append(recentEvents.trim()).append('\n');
        }

        sb.append("\nDecide your next action now. JSON only.");
        return sb.toString();
    }
}
