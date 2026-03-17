package com.aiworld.npc;

import com.aiworld.core.World;
import com.aiworld.decision.DecisionEngine;

/**
 * AbstractNPC is the base class for all NPC agents in the simulation.
 *
 * Subclasses can override {@link #onPreUpdate} and {@link #onPostUpdate}
 * to inject specialized behavior (e.g., a LeaderNPC that broadcasts
 * goals to followers before taking its own action).
 *
 * The three core systems — state, goals, memory — are wired together here,
 * with the decision engine orchestrating them each tick.
 */
public abstract class AbstractNPC {

    protected final String         id;
    protected final NPCState       state;
    protected final GoalSystem     goalSystem;
    protected final Memory         memory;
    protected final DecisionEngine decisionEngine;

    protected AbstractNPC(String id, NPCState state,
                          GoalSystem goalSystem, Memory memory) {
        this.id             = id;
        this.state          = state;
        this.goalSystem     = goalSystem;
        this.memory         = memory;
        this.decisionEngine = new DecisionEngine();
    }

    /**
     * Main update entry point called by {@link World} each tick.
     *
     * Sequence:
     *  1. Passive state effects (metabolism, aging)
     *  2. Goal weight updates
     *  3. Pre-update hook (subclass logic)
     *  4. Decision engine selects and executes action
     *  5. Memory maintenance (impression decay)
     *  6. Post-update hook
     */
    public final void update(World world) {
        if (state.isDead()) return;

        state.passiveTick();                                  // 1. metabolism
        goalSystem.updateAll(state, world.getCurrentTick());  // 2. goal update
        onPreUpdate(world);                                   // 3. hook
        decisionEngine.decide(this, world);                   // 4. act
        memory.decayImpressions();                            // 5. social drift
        onPostUpdate(world);                                  // 6. hook
    }

    /**
     * Called before decision-making each tick.
     * Override to perceive the environment, update beliefs, or
     * communicate with group members.
     */
    protected void onPreUpdate(World world) { /* default: no-op */ }

    /**
     * Called after action execution each tick.
     * Override to emit events, update group state, or log telemetry.
     */
    protected void onPostUpdate(World world) { /* default: no-op */ }

    /** Returns the NPC's archetype label (e.g., "Forager", "Leader"). */
    public abstract String getArchetype();

    // ── Accessors ────────────────────────────────────────────────────

    public String      getId()         { return id; }
    public NPCState    getState()      { return state; }
    public GoalSystem  getGoalSystem() { return goalSystem; }
    public Memory      getMemory()     { return memory; }

    @Override
    public String toString() {
        return "[" + getArchetype() + ":" + id + "] " + state;
    }
}
