package com.aiworld.llm;

/**
 * Mock LLM client for testing without real API calls.
 *
 * Picks a contextually appropriate Strategy by scanning a few keywords
 * in the prompt text (food ratio, recent events, energy level) rather
 * than returning a random result. This makes tests meaningful without
 * needing network access.
 *
 * Usage:
 * <pre>
 *   npc.setLLMClient(new MockLLMClient());
 * </pre>
 */
public class MockLLMClient implements LLMClient {

    @Override
    public Strategy call(String prompt, long currentTick) {
        Strategy.Type type;
        String        intent;
        String        reason;

        // Scan for obvious signals in the prompt and respond appropriately
        if (containsAny(prompt, "Food: 0/", "food ratio: 0.0", "starvation")) {
            type   = Strategy.Type.SURVIVE;
            intent = "Immediate survival — find food before health collapses";
            reason = "Mock: food detected as zero or starvation keyword found";

        } else if (containsAny(prompt, "WAS_ATTACKED")) {
            // Retaliate if healthy enough; flee if already weakened
            boolean weakened = containsAny(prompt,
                "Health:   1/", "Health:   2/", "Health:   3/",
                "Health:   4/", "Health:   5/", "Food: 0/", "Food: 1/");
            if (weakened) {
                type   = Strategy.Type.AVOID_CONFLICT;
                intent = "Too weak to fight back — flee and recover";
                reason = "Mock: attacked while health/food critically low";
            } else {
                type   = Strategy.Type.RETALIATE;
                intent = "Strike back at the attacker while still strong";
                reason = "Mock: attacked but health is sufficient to retaliate";
            }

        } else if (containsAny(prompt, "Energy: 0/", "Energy: 1/", "Energy: 2/")) {
            type   = Strategy.Type.CONSERVE_ENERGY;
            intent = "Rest and recover energy before attempting further actions";
            reason = "Mock: energy critically low";

        } else if (containsAny(prompt, "None.", "Nearby NPCs (radius 3): None")) {
            type   = Strategy.Type.EXPLORE;
            intent = "Roam to discover resources and other NPCs";
            reason = "Mock: no nearby NPCs detected — explore to find them";

        } else if (containsAny(prompt, "trust: 0.7", "trust: 0.8", "trust: 0.9")) {
            type   = Strategy.Type.SEEK_ALLIES;
            intent = "Deepen alliances with trusted nearby NPCs";
            reason = "Mock: existing high-trust relationships detected";

        } else {
            type   = Strategy.Type.GATHER_FOOD;
            intent = "Collect food to maintain comfortable resource levels";
            reason = "Mock: default strategy — food gathering is always useful";
        }

        return new Strategy(type, intent, reason, currentTick);
    }

    @Override
    public String callRaw(String prompt) {
        // Return a mock dialog JSON based on tone inferred from the prompt
        String speakerLine;
        String listenerLine;
        double valence;

        // Hostile tone: attacked/robbed events, or "enemy" relationship label
        // (DialogPromptBuilder writes "enemy" only when trust < 0.35)
        if (containsAny(prompt, "WAS_ATTACKED", "STOLE_FOOD", "enemy")) {
            speakerLine  = "Stay away from my resources.";
            listenerLine = "I wasn't looking for trouble.";
            valence      = -0.4;
        } else if (containsAny(prompt, "Food: 0", "starvation")) {
            speakerLine  = "Do you have any food to spare?";
            listenerLine = "I can lead you to a resource nearby.";
            valence      = 0.5;
        } else {
            speakerLine  = "Things seem quiet around here today.";
            listenerLine = "For now. I would not get too comfortable.";
            valence      = 0.1;
        }

        return "{\"speaker_line\": \"" + speakerLine + "\", "
             + "\"listener_line\": \"" + listenerLine + "\", "
             + "\"valence\": " + valence + "}";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
