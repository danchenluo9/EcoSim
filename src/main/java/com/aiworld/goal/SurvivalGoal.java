package com.aiworld.goal;

import com.aiworld.npc.NPCState;

/**
 * SurvivalGoal — keeps the NPC alive.
 *
 * Urgency spikes when food, energy, or health fall below critical thresholds.
 * This goal almost always dominates at low resource levels, ensuring NPCs
 * prioritize eating and resting over socializing when desperate.
 */
public class SurvivalGoal implements Goal {

    private static final double CRITICAL_THRESHOLD = 0.25;
    private double weight;

    public SurvivalGoal(double initialWeight) {
        this.weight = initialWeight;
    }

    @Override
    public String getName() { return "Survival"; }

    @Override
    public double computeUrgency(NPCState state) {
        // Urgency is the inverse of the minimum vital stat
        double minVital = Math.min(
            Math.min(state.getEnergyRatio(), state.getFoodRatio()),
            state.getHealthRatio()
        );
        // Exponential urgency curve — critical below 25%
        if (minVital < CRITICAL_THRESHOLD) {
            return 1.0 - (minVital / CRITICAL_THRESHOLD) * 0.5; // 0.5 – 1.0
        }
        return (1.0 - minVital) * 0.5; // 0.0 – 0.5 when comfortable
    }

    @Override
    public void updateWeight(NPCState state, long currentTick) {
        // Survival weight is stable — it's always relevant
    }

    @Override
    public double getWeight() { return weight; }
}
