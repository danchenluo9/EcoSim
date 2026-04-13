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
        SURVIVE,
        /** Strike back at an attacker — high attack priority, pursue the aggressor. */
        RETALIATE
    }

    // ── Fields ────────────────────────────────────────────────────────

    private final Type   type;
    private final String intent;    // what the NPC is trying to achieve
    private final String reason;    // LLM's explanation (for logging)
    private final long   setAtTick; // tick when this strategy was adopted

    public Strategy(Type type, String intent, String reason, long setAtTick) {
        this.type      = type;
        this.intent    = intent;
        this.reason    = reason;
        this.setAtTick = setAtTick;
    }

    // ── Action multipliers ────────────────────────────────────────────

    /**
     * Returns a multiplier (> 1.0 = boost, < 1.0 = suppress) to apply to
     * an action's estimated utility score inside the DecisionEngine.
     *
     * For most actions, the lookup key is {@code Action.getName()} (e.g., "Gather",
     * "Rest", "Interact"). For {@link com.aiworld.action.MoveAction}, the
     * {@link com.aiworld.decision.DecisionEngine} passes a reason-tagged key
     * such as {@code "Move:FOOD"} or {@code "Move:MEDICINE"}, allowing each
     * strategy to treat survival-critical movement differently from exploratory
     * or social movement.
     *
     * Move reason multiplier design intent:
     *  - FOOD / MEDICINE reasons are never suppressed below 1.0 for survival
     *    strategies (GATHER_FOOD, SURVIVE, CONSERVE_ENERGY) — these strategies
     *    should accelerate reaching resources, not block the path to them.
     *  - ALLY reason is boosted by social strategies, suppressed by survival ones.
     *  - EXPLORE reason is suppressed by everything except EXPLORE itself.
     *  - The legacy "Move" key (produced when MoveAction.getName() is used
     *    directly) acts as a fallback and is never reached in normal operation.
     */
    public double getActionMultiplier(String actionName) {
        // Reason-tagged move keys ("Move:FOOD", "Move:MEDICINE", ...) are passed
        // directly from DecisionEngine and preserved as-is.
        // Display-name moves ("Move -> (x,y)") are normalised to the legacy "Move"
        // key as a safety fallback — not reached in normal operation.
        // Attack/Steal prefixes are stripped to their base names.
        String normalised = actionName.startsWith("Move:")    ? actionName
                          : actionName.startsWith("Move -> ") ? "Move"
                          : actionName.startsWith("Attack:")  ? "Attack"
                          : actionName.startsWith("Steal:")   ? "Steal"
                          : actionName;

        switch (type) {
            case GATHER_FOOD:
                switch (normalised) {
                    case "Gather":         return 2.5;
                    case "Rest":           return 0.8;
                    case "Move:FOOD":      return 2.0;  // boost toward food — that's the whole point
                    case "Move:MEDICINE":  return 1.5;  // don't suppress a health emergency
                    case "Move:ALLY":      return 0.3;  // suppress social wandering
                    case "Move:EXPLORE":   return 0.3;  // suppress aimless movement
                    case "Move":           return 0.6;  // legacy fallback
                    case "Interact":       return 0.2;
                    case "Steal":          return 1.8;  // steal supplements gathering when food is scarce
                }
                break;
            case SEEK_ALLIES:
                switch (normalised) {
                    case "Gather":         return 0.7;
                    case "Rest":           return 0.5;
                    case "Move:FOOD":      return 0.9;  // mild suppression — food is secondary to allies
                    case "Move:MEDICINE":  return 1.5;  // health emergency overrides alliance seeking
                    case "Move:ALLY":      return 2.5;  // strongly boost movement toward allies
                    case "Move:EXPLORE":   return 0.8;  // mild suppression — random wandering less useful
                    case "Move":           return 1.5;  // legacy fallback
                    case "Interact":       return 2.5;
                    case "Steal":          return 0.1;  // don't steal from potential allies
                }
                break;
            case EXPLORE:
                switch (normalised) {
                    case "Gather":         return 1.0;
                    case "Rest":           return 0.5;
                    case "Move:FOOD":      return 1.3;  // food-seeking compatible with exploring
                    case "Move:MEDICINE":  return 1.5;  // health emergency always takes priority
                    case "Move:ALLY":      return 1.0;  // neutral — meeting people while exploring is fine
                    case "Move:EXPLORE":   return 3.0;  // exploration is the point
                    case "Move":           return 2.5;  // legacy fallback
                    case "Interact":       return 0.8;
                    case "Steal":          return 0.5;  // mild suppression — exploring, not raiding
                }
                break;
            case AVOID_CONFLICT:
                switch (normalised) {
                    case "Gather":         return 1.2;
                    case "Rest":           return 1.0;
                    case "Move:FOOD":      return 1.4;  // foraging movement keeps NPC moving and fed
                    case "Move:MEDICINE":  return 1.5;  // wounded NPCs must reach medicine
                    case "Move:ALLY":      return 1.2;  // moving to an ally is relatively safe
                    case "Move:EXPLORE":   return 1.5;  // keep moving to put distance between threats
                    case "Move":           return 1.5;  // legacy fallback
                    case "Interact":       return 0.1;  // avoid physical cooperation — too exposed
                    case "Steal":          return 0.1;  // stealing escalates conflict — avoid
                }
                break;
            case CONSERVE_ENERGY:
                switch (normalised) {
                    case "Gather":         return 1.5;
                    case "Rest":           return 2.5;
                    case "Move:FOOD":      return 1.3;  // need to eat even when conserving — going to food is worth it
                    case "Move:MEDICINE":  return 1.5;  // health emergency always takes priority over rest
                    case "Move:ALLY":      return 0.2;  // suppress social movement — burns energy without return
                    case "Move:EXPLORE":   return 0.2;  // suppress aimless movement — costs energy for nothing
                    case "Move":           return 0.3;  // legacy fallback
                    case "Interact":       return 0.5;
                    case "Steal":          return 1.2;  // low-energy food acquisition
                }
                break;
            case SURVIVE:
                switch (normalised) {
                    case "Gather":         return 2.5;
                    case "Rest":           return 1.5;
                    case "Move:FOOD":      return 2.0;  // reaching food IS surviving — strongly boost
                    case "Move:MEDICINE":  return 2.0;  // reaching medicine IS surviving — strongly boost
                    case "Move:ALLY":      return 0.3;  // suppress social movement in emergency
                    case "Move:EXPLORE":   return 0.2;  // suppress aimless movement in emergency
                    case "Move":           return 0.7;  // legacy fallback
                    case "Interact":       return 0.1;
                    case "Steal":          return 2.0;  // desperate — take food by any means
                }
                break;
            case RETALIATE:
                switch (normalised) {
                    case "Gather":         return 0.5;
                    case "Rest":           return 0.5;
                    case "Move:FOOD":      return 0.6;  // mild suppression — focused on combat, not foraging
                    case "Move:MEDICINE":  return 1.5;  // stay healthy to fight effectively
                    case "Move:ALLY":      return 1.0;  // neutral — allies may help in the fight
                    case "Move:EXPLORE":   return 1.5;  // pursuing enemy = active movement
                    case "Move":           return 1.3;  // legacy fallback
                    case "Interact":       return 0.3;
                    case "Steal":          return 0.3;  // focused on fighting, not sneaking
                }
                break;
        }
        // Attack: suppressed by most strategies, boosted only when desperate
        if (normalised.equals("Attack")) {
            switch (type) {
                case AVOID_CONFLICT:   return 0.1;
                case SEEK_ALLIES:      return 0.2;
                case CONSERVE_ENERGY:  return 0.3;
                case GATHER_FOOD:      return 0.5;
                case EXPLORE:          return 0.6;
                case SURVIVE:          return 1.5;
                case RETALIATE:        return 2.0;
            }
        }
        return 1.0; // unknown action — no bias
    }

    // ── Factory ───────────────────────────────────────────────────────

    /** Default strategy used when no LLM decision has been made yet. */
    public static Strategy defaultStrategy(long tick) {
        return new Strategy(Type.EXPLORE, "Default exploratory behaviour",
                            "No LLM strategy set yet", tick);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public Type   getType()      { return type; }
    public String getIntent()    { return intent; }
    public String getReason()    { return reason; }
    public long   getSetAtTick() { return setAtTick; }

    @Override
    public String toString() {
        return "Strategy{" + type + ", intent='" + intent + "'}";
    }
}
