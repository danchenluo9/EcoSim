package com.aiworld.model;

/**
 * Represents one NPC's subjective impression of another NPC.
 * Trust and hostility levels drift over time based on shared experiences.
 * This is the foundation for alliance formation and group dynamics.
 */
public class NPCImpression {

    private final String targetNpcId;
    private double trust;             // 0.0 (none) to 1.0 (fully trusted)
    private double hostility;         // 0.0 (neutral) to 1.0 (enemy)
    private int    interactionCount;

    public NPCImpression(String targetNpcId) {
        this.targetNpcId      = targetNpcId;
        this.trust            = 0.5;  // start neutral
        this.hostility        = 0.0;
        this.interactionCount = 0;
    }

    /** Positive interaction (sharing, helping) increases trust. */
    public void recordPositiveInteraction(double delta) {
        trust    = Math.min(1.0, trust + delta);
        hostility = Math.max(0.0, hostility - delta * 0.5);
        interactionCount++;
    }

    /** Negative interaction (theft, attack) increases hostility. */
    public void recordNegativeInteraction(double delta) {
        hostility = Math.min(1.0, hostility + delta);
        trust     = Math.max(0.0, trust - delta * 0.5);
        interactionCount++;
    }

    /** Trust decays naturally if NPCs don't interact — simulates social forgetting. */
    public void decayOverTime(double decayRate) {
        trust = Math.max(0.0, trust - decayRate);
    }

    public String getTargetNpcId()     { return targetNpcId; }
    public double getTrust()           { return trust; }
    public double getHostility()       { return hostility; }
    public int    getInteractionCount(){ return interactionCount; }
}
