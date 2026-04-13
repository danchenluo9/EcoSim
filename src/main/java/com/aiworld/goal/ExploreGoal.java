package com.aiworld.goal;

import com.aiworld.npc.NPCState;

/**
 * ExploreGoal — drives the NPC to move around and discover resources/allies.
 *
 * Urgency is low when the NPC is comfortable but rises when the NPC has
 * been stationary too long (boredom) or when it needs to find new resources.
 * This goal is the primary driver of emergent migration behavior.
 */
public class ExploreGoal implements Goal {

    private final double weight;
    private long         ticksStationary;  // how many ticks NPC hasn't moved
    private static final long BOREDOM_THRESHOLD = 10;

    public ExploreGoal(double initialWeight) {
        this.weight         = initialWeight;
        this.ticksStationary = 0;
    }

    @Override
    public String getName() { return "Explore"; }

    @Override
    public double computeUrgency(NPCState state) {
        // Boredom factor: urgency grows the longer NPC stays still
        double boredomFactor = Math.min(1.0, (double) ticksStationary / BOREDOM_THRESHOLD);
        // Don't explore if survival is critical
        double energyPenalty = state.getEnergyRatio() < 0.2 ? 0.3 : 1.0;
        return boredomFactor * weight * energyPenalty;
    }

    @Override
    public void onTick(NPCState state, long currentTick) {
        // Only accumulate boredom when the NPC is physically capable of moving.
        // If energy is too low to move (MoveAction requires energy > 5), the counter
        // would grow indefinitely and lock urgency at max — producing a stuck state
        // where "explore" always wins even though the NPC literally cannot move.
        if (state.getEnergy() > 5) {
            ticksStationary++;
        }
    }

    /** Called by NPC when it successfully moves to reset boredom counter. */
    public void onMoved() {
        ticksStationary = 0;
    }

}
