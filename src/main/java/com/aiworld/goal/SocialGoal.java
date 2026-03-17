package com.aiworld.goal;

import com.aiworld.npc.NPCState;

/**
 * SocialGoal — motivates the NPC to seek interactions with others.
 *
 * High social urgency leads to alliance formation, cooperative resource
 * gathering, and group migration. NPCs with low trust capital become
 * isolated — an emergent social stratification mechanic.
 */
public class SocialGoal implements Goal {

    private double weight;
    private long   ticksSinceLastInteraction;
    private static final long LONELINESS_THRESHOLD = 20;

    public SocialGoal(double initialWeight) {
        this.weight                    = initialWeight;
        this.ticksSinceLastInteraction = 0;
    }

    @Override
    public String getName() { return "Social"; }

    @Override
    public double computeUrgency(NPCState state) {
        // Loneliness grows over time without interaction
        double lonelinessFactor = Math.min(1.0,
            (double) ticksSinceLastInteraction / LONELINESS_THRESHOLD);
        // Social needs are a luxury — only if basic needs are somewhat met
        double comfortFactor = state.getEnergyRatio() > 0.4 ? 1.0 : 0.2;
        return lonelinessFactor * weight * comfortFactor;
    }

    @Override
    public void updateWeight(NPCState state, long currentTick) {
        ticksSinceLastInteraction++;
    }

    /** Called when the NPC completes a social interaction. */
    public void onInteracted() {
        ticksSinceLastInteraction = 0;
    }

    @Override
    public double getWeight() { return weight; }
}
