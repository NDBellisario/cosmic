package server.bots.planner;

import java.util.Locale;

/**
 * The fixed vocabulary of decisions the autonomous planner may emit. Each value
 * maps onto an existing {@code BotManager} public hook / {@code BotTask} (see
 * {@link BotAutonomousPlanner#apply}). The planner never invents behavior — it
 * only chooses among these and the existing framework executes them.
 *
 * Some actions carry parameters supplied in the SLM JSON {@code params} object:
 *   GRIND_AT     → {"mapId": <int>}   (travel+grind at a specific map)
 *   TRAVEL_TO    → {"mapId": <int>}
 *   TURN_IN_QUEST→ {"npcId": <int>}
 * The rest are parameterless.
 */
public enum PlannerAction {
    GRIND,
    GRIND_AT,
    TRAVEL_TO,
    FOLLOW_OWNER,
    STOP,
    BUY_POTIONS,
    SELL,
    TURN_IN_QUEST,
    REST_IN_TOWN,
    IDLE;

    /** Case-insensitive lookup; returns null on no match so the parser can fall back. */
    static PlannerAction fromString(String s) {
        if (s == null) return null;
        try {
            return PlannerAction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
