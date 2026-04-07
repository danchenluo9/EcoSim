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
    private final double weight;

    public SurvivalGoal(double initialWeight) {
        this.weight = initialWeight;
    }

    @Override
    public String getName() { return "Survival"; }

    @Override
    public double computeUrgency(NPCState state) {
        // Urgency is driven by food and health — the long-cycle survival stats.
        // Energy is deliberately excluded: it is a short-cycle stat (resting restores
        // 20 units in one action) and mixing it in caused "tired but fed" NPCs to
        // enter full critical-survival mode, suppressing social behaviour unnecessarily.
        double minVital = Math.min(state.getFoodRatio(), state.getHealthRatio());

        // Exponential urgency curve — critical below 25%
        if (minVital < CRITICAL_THRESHOLD) {
            return (1.0 - (minVital / CRITICAL_THRESHOLD) * 0.5) * weight; // 0.5w – 1.0w
        }
        return (1.0 - minVital) * 0.5 * weight; // 0.0 – 0.5w when comfortable
    }

    @Override
    public void onTick(NPCState state, long currentTick) {
        // Survival weight is a fixed personality trait — no per-tick state to update.
    }

}
