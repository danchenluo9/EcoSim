package com.aiworld.llm;

/**
 * High-level strategic directive produced by the LLM.
 *
 * A Strategy does NOT specify which action to take — the existing
 * DecisionEngine still handles that every tick. Instead it biases the
 * action-utility scores via {@link #getActionMultiplier}, causing the
 * rule engine to prefer actions that align with the strategy.
 *
 * Example: Strategy.Type.GATHER_FOOD multiplies Gather utility × 2.0 and
 * Interact utility × 0.3, so the NPC naturally gravitates toward food
 * without bypassing any safety checks.
 *
 * A single Strategy typically lasts 30–60 ticks before being re-evaluated.
 */
public class Strategy {

    // ── Strategy types ────────────────────────────────────────────────

    public enum Type {
        /** Prioritise food collection; suppress socialising and movement. */
        GATHER_FOOD,
        /** Seek out trusted allies; boost movement toward and interaction with NPCs. */
        SEEK_ALLIES,
        /** Roam to discover new resources and territory. */
        EXPLORE,
        /** Avoid hostile encounters; reduce interaction, increase movement away. */
        AVOID_CONFLICT,
        /** Minimise energy expenditure; prefer resting and gathering in place. */
        CONSERVE_ENERGY,
        /** Emergency survival mode: gather and rest above everything else. */
        SURVIVE
    }

    // ── Fields ────────────────────────────────────────────────────────

    private final Type   type;
    private final String intent;    // what the NPC is trying to achieve
    private final String target;    // optional: NPC id or zone name to focus on
    private final String reason;    // LLM's explanation (for logging)
    private final long   setAtTick; // tick when this strategy was adopted

    public Strategy(Type type, String intent, String target, String reason, long setAtTick) {
        this.type      = type;
        this.intent    = intent;
        this.target    = target;
        this.reason    = reason;
        this.setAtTick = setAtTick;
    }

    // ── Action multipliers ────────────────────────────────────────────

    /**
     * Returns a multiplier (> 1.0 = boost, < 1.0 = suppress) to apply to
     * an action's estimated utility score inside the DecisionEngine.
     *
     * Action names match Action.getName(): "Gather", "Rest", "Move", "Interact".
     */
    public double getActionMultiplier(String actionName) {
        // MoveAction.getName() returns "Move -> (x, y)" — match by prefix
        String normalised = actionName.startsWith("Move") ? "Move" : actionName;

        switch (type) {
            case GATHER_FOOD:
                switch (normalised) {
                    case "Gather":   return 2.5;
                    case "Rest":     return 0.8;
                    case "Move":     return 0.6;
                    case "Interact": return 0.2;
                }
                break;
            case SEEK_ALLIES:
                switch (normalised) {
                    case "Gather":   return 0.7;
                    case "Rest":     return 0.5;
                    case "Move":     return 1.5;
                    case "Interact": return 2.5;
                }
                break;
            case EXPLORE:
                switch (normalised) {
                    case "Gather":   return 1.0;
                    case "Rest":     return 0.5;
                    case "Move":     return 2.5;
                    case "Interact": return 0.8;
                }
                break;
            case AVOID_CONFLICT:
                switch (normalised) {
                    case "Gather":   return 1.2;
                    case "Rest":     return 1.0;
                    case "Move":     return 1.5;
                    case "Interact": return 0.1;
                }
                break;
            case CONSERVE_ENERGY:
                switch (normalised) {
                    case "Gather":   return 1.5;
                    case "Rest":     return 2.5;
                    case "Move":     return 0.3;
                    case "Interact": return 0.5;
                }
                break;
            case SURVIVE:
                switch (normalised) {
                    case "Gather":   return 2.5;
                    case "Rest":     return 1.5;
                    case "Move":     return 0.7;
                    case "Interact": return 0.1;
                }
                break;
        }
        // Dialog is treated identically to Interact across all strategy types
        // (both are social actions — suppress in survival, boost when seeking allies)
        if (normalised.equals("Dialog")) {
            return getActionMultiplier("Interact");
        }
        return 1.0; // unknown action — no bias
    }

    // ── Factory ───────────────────────────────────────────────────────

    /** Default strategy used when no LLM decision has been made yet. */
    public static Strategy defaultStrategy(long tick) {
        return new Strategy(Type.EXPLORE, "Default exploratory behaviour",
                            "", "No LLM strategy set yet", tick);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public Type   getType()      { return type; }
    public String getIntent()    { return intent; }
    public String getTarget()    { return target; }
    public String getReason()    { return reason; }
    public long   getSetAtTick() { return setAtTick; }

    @Override
    public String toString() {
        return "Strategy{" + type + ", intent='" + intent + "', target='" + target + "'}";
    }
}
