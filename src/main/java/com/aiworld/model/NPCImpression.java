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

    /**
     * Trust and hostility both decay naturally — simulates social forgetting.
     *
     * Trust decays toward 0.5 (neutral), not toward 0.0.
     * An NPC you once knew but haven't seen in a while becomes a stranger
     * again — not an enemy. Decaying to 0.0 would wrongly trigger competitive
     * interactions with forgotten allies.
     *
     * Hostility decays toward 0.0 (no hostility) — old grudges fade completely.
     *
     * The effective rate is scaled down by interaction depth: NPCs who have
     * shared many experiences have more stable relationships. A stranger
     * (interactionCount=0) decays at the full base rate; a close ally
     * (interactionCount=20+) decays significantly slower, reflecting that
     * established trust is harder to erode through mere absence.
     *
     * Formula: effectiveRate = baseRate / (1 + log(1 + interactionCount))
     * Example decay rates relative to base:
     *   0 interactions  → ÷1.0 (full rate — strangers)
     *   7 interactions  → ÷3.1
     *   20 interactions → ÷4.0
     *   50 interactions → ÷4.9 (diminishing returns above ~20)
     */
    public void decayOverTime(double decayRate) {
        double effectiveRate = decayRate / (1.0 + Math.log1p(interactionCount));
        trust     = trust > 0.5
                  ? Math.max(0.5, trust     - effectiveRate)
                  : Math.min(0.5, trust     + effectiveRate);
        hostility = Math.max(0.0, hostility - effectiveRate * 0.5); // hostility fades slower
    }

    public String getTargetNpcId()     { return targetNpcId; }
    public double getTrust()           { return trust; }
    public double getHostility()       { return hostility; }
    public int    getInteractionCount(){ return interactionCount; }
}
