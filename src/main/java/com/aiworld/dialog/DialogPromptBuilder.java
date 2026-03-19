package com.aiworld.dialog;

import com.aiworld.core.World;
import com.aiworld.llm.StrategyManager;
import com.aiworld.npc.AbstractNPC;
import com.aiworld.npc.NPCState;

import java.util.List;

/**
 * Builds the LLM prompt for generating dialog between two NPCs.
 *
 * The prompt gives Claude both NPCs' current state, their relationship,
 * and their conversation history so the generated lines feel grounded
 * in the simulation's events rather than generic.
 *
 * Expected model output:
 * <pre>
 * {
 *   "speaker_line":  "...",
 *   "listener_line": "...",
 *   "valence": 0.3
 * }
 * </pre>
 *
 * valence: -1.0 (hostile/threatening) to +1.0 (warm/cooperative)
 */
public class DialogPromptBuilder {

    private static final int MAX_PAST_CONVERSATIONS = 3;

    public static String build(AbstractNPC speaker, AbstractNPC listener, World world) {
        NPCState spState = speaker.getState();
        NPCState liState = listener.getState();
        StringBuilder sb = new StringBuilder(1024);

        sb.append("Two NPC agents in a survival simulation are having a conversation.\n");
        sb.append("Generate ONE line of dialog for each — natural, in-character, and brief (under 15 words each).\n\n");

        // ── Speaker ───────────────────────────────────────────────────
        sb.append("=== ").append(speaker.getId()).append(" (speaking first) ===\n");
        sb.append(String.format("Health: %d/100, Energy: %d/100, Food: %d/100%n",
            spState.getHealth(), spState.getEnergy(), spState.getFood()));

        StrategyManager spSm = speaker.getStrategyManager();
        if (spSm != null) {
            sb.append("Current goal: ").append(spSm.getCurrentStrategy().getIntent()).append("\n");
        }
        sb.append("\n");

        // ── Listener ──────────────────────────────────────────────────
        sb.append("=== ").append(listener.getId()).append(" (responding) ===\n");
        sb.append(String.format("Health: %d/100, Energy: %d/100, Food: %d/100%n",
            liState.getHealth(), liState.getEnergy(), liState.getFood()));

        StrategyManager liSm = listener.getStrategyManager();
        if (liSm != null) {
            sb.append("Current goal: ").append(liSm.getCurrentStrategy().getIntent()).append("\n");
        }
        sb.append("\n");

        // ── Relationship ──────────────────────────────────────────────
        double trust = speaker.getMemory()
            .getImpression(listener.getId())
            .map(imp -> imp.getTrust())
            .orElse(0.5);
        double hostility = speaker.getMemory()
            .getImpression(listener.getId())
            .map(imp -> imp.getHostility())
            .orElse(0.0);

        sb.append("=== Relationship ===\n");
        sb.append(String.format("%s's trust in %s: %.2f%n", speaker.getId(), listener.getId(), trust));
        sb.append(String.format("%s's hostility toward %s: %.2f%n", speaker.getId(), listener.getId(), hostility));
        sb.append(String.format("(0.0=stranger, 0.5=neutral, 1.0=%s)%n",
            trust > 0.5 ? "close ally" : "enemy"));
        sb.append("\n");

        // ── Past conversations ────────────────────────────────────────
        List<DialogSession> past = speaker.getMemory().getRecentConversations(MAX_PAST_CONVERSATIONS);
        List<DialogSession> relevant = past.stream()
            .filter(s -> s.getListenerId().equals(listener.getId())
                      || s.getSpeakerId().equals(listener.getId()))
            .toList();

        if (!relevant.isEmpty()) {
            sb.append("=== Past conversations between them ===\n");
            relevant.forEach(s -> sb.append("  ").append(s).append("\n"));
            sb.append("\n");
        }

        // ── Dialog rules ──────────────────────────────────────────────
        sb.append("=== Dialog rules ===\n");
        sb.append("- Reflect each NPC's urgency and relationship naturally\n");
        sb.append("- Do NOT mention game mechanics (food ratios, health points, etc.)\n");
        sb.append("- Keep it grounded: these are survival-driven characters\n");
        sb.append("- If trust is low or hostility is high, the tone should be tense or guarded\n");
        sb.append("- If trust is high, the tone should be warm or collaborative\n\n");

        // ── Output format ─────────────────────────────────────────────
        sb.append("Respond ONLY with this JSON (no extra text):\n");
        sb.append("{\n");
        sb.append("  \"speaker_line\":  \"<").append(speaker.getId()).append("'s line>\",\n");
        sb.append("  \"listener_line\": \"<").append(listener.getId()).append("'s response>\",\n");
        sb.append("  \"valence\": <float from -1.0 to 1.0 reflecting the tone>\n");
        sb.append("}");

        return sb.toString();
    }
}
