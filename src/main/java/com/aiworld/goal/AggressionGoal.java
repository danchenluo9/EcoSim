package com.aiworld.goal;

import com.aiworld.npc.NPCState;

/**
 * AggressionGoal — drives the NPC to seek and attack nearby NPCs.
 *
 * Urgency is proportional to the weight and the NPC's current health.
 * A wounded fighter suppresses aggression to avoid dying; a healthy
 * one is always ready to fight.
 *
 * Used exclusively by the Fighter archetype. AttackAction reads this
 * urgency to boost its utility score and lower its execution threshold.
 */
public class AggressionGoal implements Goal {

    private final double weight;

    public AggressionGoal(double weight) {
        this.weight = weight;
    }

    @Override
    public String getName() { return "Aggression"; }

    @Override
    public double computeUrgency(NPCState state) {
        // Suppress aggression when badly wounded — no point fighting at 20% health
        double readiness = state.getHealthRatio() > 0.5
            ? 1.0
            : state.getHealthRatio() / 0.5;
        return weight * readiness;
    }

    @Override
    public void onTick(NPCState state, long currentTick) {
        // Aggression is a fixed personality trait — no per-tick state to update.
    }

}
