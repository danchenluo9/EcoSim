package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.dialog.DialogPromptBuilder;
import com.aiworld.dialog.DialogSession;
import com.aiworld.goal.SocialGoal;
import com.aiworld.llm.LLMClient;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;

import java.util.List;

/**
 * DialogAction — NPC initiates an LLM-generated conversation with a nearby NPC.
 *
 * Dialog is purely social: no resources change hands, but the conversation
 * affects trust and is recorded in both NPCs' memory for future context.
 *
 * Cooldown: enforced via HAD_CONVERSATION memory events — an NPC won't
 * start a new dialog within {@value #DIALOG_COOLDOWN_TICKS} ticks of the last one.
 *
 * Invoked as a secondary action from AbstractNPC.update() via {@link #tryExecute}
 * after the main action runs, so NPCs can converse and act in the same tick.
 */
public class DialogAction implements Action {

    private static final int    DIALOG_RADIUS         = 2;
    private static final int    DIALOG_COOLDOWN_TICKS = 15;
    private static final double TRUST_SCALE           = 0.08;

    @Override
    public String getName() { return "Dialog"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        if (npc.getLLMClient() == null) return false;

        // Cooldown: skip if a conversation happened recently
        boolean recentlyTalked = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.HAD_CONVERSATION)
            .stream()
            .anyMatch(e -> world.getCurrentTick() - e.getTick() < DIALOG_COOLDOWN_TICKS);
        if (recentlyTalked) return false;

        List<AbstractNPC> nearby = world.getNPCsNear(npc.getState().getLocation(), DIALOG_RADIUS);
        nearby.remove(npc);
        return !nearby.isEmpty();
    }

    @Override
    public void execute(AbstractNPC speaker, World world) {
        List<AbstractNPC> nearby = world.getNPCsNear(
            speaker.getState().getLocation(), DIALOG_RADIUS);
        nearby.remove(speaker);
        if (nearby.isEmpty()) return;

        AbstractNPC listener = nearby.get(0);
        LLMClient   client   = speaker.getLLMClient();
        if (client == null) return;

        String prompt   = DialogPromptBuilder.build(speaker, listener, world);
        String rawText  = client.callRaw(prompt);
        String[] parsed = parseDialogJson(rawText);

        if (parsed == null) {
            System.out.printf("[%s] Dialog LLM call failed — skipping%n", speaker.getId());
            return;
        }

        String speakerLine  = parsed[0];
        String listenerLine = parsed[1];
        double valence      = Double.parseDouble(parsed[2]);

        // ── Print the conversation ────────────────────────────────────
        System.out.println();
        System.out.printf("  [%s -> %s]%n", speaker.getId(), listener.getId());
        System.out.printf("  %s: \"%s\"%n", speaker.getId(),  speakerLine);
        System.out.printf("  %s: \"%s\"%n", listener.getId(), listenerLine);
        System.out.println();

        // ── Update trust based on conversation tone ───────────────────
        double delta = valence * TRUST_SCALE;
        if (delta > 0) {
            speaker.getMemory().recordPositiveInteraction(listener.getId(), delta);
            listener.getMemory().recordPositiveInteraction(speaker.getId(), delta);
        } else if (delta < 0) {
            double absDelta = Math.abs(delta);
            speaker.getMemory().recordNegativeInteraction(listener.getId(), absDelta);
            listener.getMemory().recordNegativeInteraction(speaker.getId(), absDelta);
        }

        // ── Record memory events ──────────────────────────────────────
        long tick = world.getCurrentTick();
        speaker.getMemory().addEvent(new MemoryEvent(tick,
            MemoryEvent.EventType.HAD_CONVERSATION,
            "Talked with " + listener.getId() + ": \"" + speakerLine + "\"",
            speaker.getState().getLocation(), valence));

        listener.getMemory().addEvent(new MemoryEvent(tick,
            MemoryEvent.EventType.HAD_CONVERSATION,
            "Talked with " + speaker.getId() + ": \"" + listenerLine + "\"",
            listener.getState().getLocation(), valence));

        // ── Store in conversation logs ────────────────────────────────
        DialogSession session = new DialogSession(
            tick, speaker.getId(), listener.getId(),
            speakerLine, listenerLine, valence);
        speaker.getMemory().addConversation(session);
        listener.getMemory().addConversation(session);

        // ── Reset loneliness counter — talking counts as social interaction ──
        speaker.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);
        listener.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);
    }

    /**
     * Secondary-action entry point: runs dialog only if conditions allow.
     * Called directly from AbstractNPC.update() after the main action.
     */
    public void tryExecute(AbstractNPC npc, World world) {
        if (canExecute(npc, world)) {
            execute(npc, world);
        }
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        return npc.getGoalSystem().getUrgency("Social", npc.getState()) * 0.85;
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Parses {"speaker_line":"...","listener_line":"...","valence":0.3}
     * Returns [speakerLine, listenerLine, valenceStr] or null on failure.
     */
    private String[] parseDialogJson(String text) {
        if (text == null || text.isBlank()) return null;

        String speakerLine  = extractField(text, "speaker_line");
        String listenerLine = extractField(text, "listener_line");
        String valenceStr   = extractNumber(text, "valence");

        if (speakerLine == null || listenerLine == null) return null;

        double valence;
        try {
            valence = valenceStr != null ? Double.parseDouble(valenceStr) : 0.0;
            valence = Math.max(-1.0, Math.min(1.0, valence));
        } catch (NumberFormatException e) {
            valence = 0.0;
        }

        return new String[]{ speakerLine, listenerLine, String.valueOf(valence) };
    }

    private String extractField(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '"' && json.charAt(quoteEnd - 1) != '\\') break;
            quoteEnd++;
        }
        if (quoteEnd >= json.length()) return null;
        return json.substring(quoteStart + 1, quoteEnd).replace("\\\"", "\"");
    }

    private String extractNumber(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && "0123456789.-".indexOf(json.charAt(end)) >= 0) end++;
        if (end == start) return null;
        return json.substring(start, end);
    }
}
