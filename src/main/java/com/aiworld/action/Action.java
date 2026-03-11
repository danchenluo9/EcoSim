package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.npc.AbstractNPC;

/**
 * An Action is a discrete behavior an NPC can execute in a single tick.
 *
 * Actions are composable and modular — new behaviors (Build, Attack,
 * Trade, Form Alliance) are added simply by implementing this interface.
 *
 * Design principle: actions are stateless descriptors; all state mutation
 * happens inside {@link #execute(AbstractNPC, World)}.
 */
public interface Action {

    /** Human-readable name for logging and UI. */
    String getName();

    /**
     * Checks whether this action can currently be performed.
     * Preconditions prevent wasted ticks (e.g., can't gather at empty node).
     *
     * @param npc   the NPC attempting the action
     * @param world current world state
     * @return true if the action is valid to execute
     */
    boolean canExecute(AbstractNPC npc, World world);

    /**
     * Performs the action, mutating NPC and/or world state.
     * Should be idempotent with respect to world consistency.
     *
     * @param npc   the NPC performing the action
     * @param world current world state
     */
    void execute(AbstractNPC npc, World world);

    /**
     * Estimated utility of this action given current state.
     * Used by {@link com.aiworld.decision.DecisionEngine} to rank candidates.
     * Higher = more desirable. Range: 0.0 – 1.0.
     *
     * @param npc   the NPC evaluating the action
     * @param world current world state
     */
    double estimatedUtility(AbstractNPC npc, World world);
}
