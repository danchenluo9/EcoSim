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

        // ── Relationship — both sides so Claude can write each NPC's line correctly ──
        double spTrust     = speaker.getMemory().getImpression(listener.getId())
                                .map(imp -> imp.getTrust()).orElse(0.5);
        double spHostility = speaker.getMemory().getImpression(listener.getId())
                                .map(imp -> imp.getHostility()).orElse(0.0);
        double liTrust     = listener.getMemory().getImpression(speaker.getId())
                                .map(imp -> imp.getTrust()).orElse(0.5);
        double liHostility = listener.getMemory().getImpression(speaker.getId())
                                .map(imp -> imp.getHostility()).orElse(0.0);

        sb.append("=== Relationship (scale: 0.0=hostile → 0.5=neutral/stranger → 1.0=close ally) ===\n");
        sb.append(String.format("%s → %s: trust %.2f, hostility %.2f%n",
            speaker.getId(), listener.getId(), spTrust, spHostility));
        sb.append(String.format("%s → %s: trust %.2f, hostility %.2f%n",
            listener.getId(), speaker.getId(), liTrust, liHostility));
        sb.append("\n");

        // ── Past conversations ────────────────────────────────────────
        // Search the full stored log (up to 20 entries) before filtering to
        // this pair — searching only the last 3 total almost always misses history
        // when the speaker recently talked to different NPCs.
        List<DialogSession> allRecent = speaker.getMemory().getRecentConversations(20);
        List<DialogSession> relevant  = allRecent.stream()
            .filter(s -> s.getListenerId().equals(listener.getId())
                      || s.getSpeakerId().equals(listener.getId()))
            .toList();
        // Keep only the most recent MAX_PAST_CONVERSATIONS of this pair
        if (relevant.size() > MAX_PAST_CONVERSATIONS) {
            relevant = relevant.subList(relevant.size() - MAX_PAST_CONVERSATIONS, relevant.size());
        }

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
