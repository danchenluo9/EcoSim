package com.aiworld.goal;

import com.aiworld.npc.NPCState;

/**
 * A Goal represents a desire that drives NPC behavior.
 *
 * Goals use a utility-based model: each goal computes an urgency score
 * based on current NPC state. The GoalSystem picks the highest-urgency
 * goal, and the DecisionEngine selects the best action to satisfy it.
 *
 * To add emergent goals (e.g., AllianceGoal, MigrationGoal),
 * simply implement this interface.
 */
public interface Goal {

    /** Unique name for this goal — used in logging and debugging. */
    String getName();

    /**
     * Computes how urgently this goal needs to be fulfilled right now.
     * Higher value = more urgent. Typical range: 0.0 – 1.0.
     *
     * @param state current NPC state (energy, food, health, etc.)
     * @return urgency score
     */
    double computeUrgency(NPCState state);

    /**
     * Called each tick to update any internal state this goal tracks
     * (e.g., SocialGoal increments a loneliness counter; ExploreGoal
     * increments a boredom counter). Most goals are stateless and leave
     * this as a no-op.
     *
     * Renamed from {@code updateWeight} — "weight" implied the goal's
     * personality weight changes dynamically, but it is final in every
     * implementation. This is purely a per-tick state hook.
     */
    void onTick(NPCState state, long currentTick);
}
