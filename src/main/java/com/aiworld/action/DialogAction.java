package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.dialog.DialogPromptBuilder;
import com.aiworld.llm.Strategy;
import com.aiworld.llm.StrategyManager;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * DialogAction — decides whether and with whom an NPC should converse.
 *
 * Execution is split into three phases (see {@link DialogTask}) so the
 * LLM HTTP call never blocks the world lock:
 *
 *  1. {@link #prepare(AbstractNPC, World)} — this class.  Checks all gates,
 *     picks the best listener, builds the prompt snapshot.
 *  2. {@link DialogTask#executeCall()}     — HTTP call outside the lock.
 *  3. {@link DialogTask#applyResult(World)} — re-applies results under lock.
 *
 * Cooldown: global — an NPC won't start ANY new dialog within
 * {@value #DIALOG_COOLDOWN_TICKS} ticks of their last one, regardless of partner.
 * This is intentional: it prevents Diplomats (re-qualifying in ~3 ticks) from
 * flooding the LLM API by chaining conversations with every nearby NPC in rapid
 * succession. The global guard is tracked via {@link #lastDialogTick} in O(1)
 * rather than scanning memory events.
 *
 * Strategy suppression: strategies that suppress socialising
 * (AVOID_CONFLICT, SURVIVE, GATHER_FOOD, RETALIATE) block dialog entirely
 * via {@link #isDialogSuppressedBy(Strategy)}.
 */
public class DialogAction {

    private static final Logger log = LoggerFactory.getLogger(DialogAction.class);

    private static final int    DIALOG_RADIUS         = 2;
    private static final int    DIALOG_COOLDOWN_TICKS = 15;
    private static final double MIN_SOCIAL_URGENCY    = 0.1;   // blocks Fighter (max ~0.05)

    /** Tick of the most recent dialog this NPC initiated. -COOLDOWN means "never talked". */
    private long lastDialogTick = -DIALOG_COOLDOWN_TICKS;

    /**
     * Phase 1 — run inside the synchronized world tick.
     *
     * Checks all preconditions, selects the most-trusted nearby listener,
     * and builds the LLM prompt from the current world snapshot.
     *
     * @return a ready-to-execute {@link DialogTask}, or {@code null} if dialog
     *         should not happen this tick.
     */
    public DialogTask prepare(AbstractNPC npc, World world) {
        if (npc.getLLMClient() == null) return null;
        if (npc.getGoalSystem().getUrgency("Social", npc.getState()) < MIN_SOCIAL_URGENCY)
            return null;

        // Respect strategy suppression — these strategies mean the NPC is too
        // focused on survival or combat to engage in conversation.
        StrategyManager sm = npc.getStrategyManager();
        if (sm != null && isDialogSuppressedBy(sm.getCurrentStrategy()))
            return null;

        // Global cooldown: O(1) field check instead of scanning memory events
        if (world.getCurrentTick() - lastDialogTick < DIALOG_COOLDOWN_TICKS) return null;

        List<AbstractNPC> nearby = world.getNPCsNear(npc, DIALOG_RADIUS);
        if (nearby.isEmpty()) return null;

        // Prefer the most-trusted nearby NPC; strangers default to 0.5 (benefit of the doubt).
        // Secondary sort by ID breaks ties deterministically without insertion-order bias.
        AbstractNPC listener = nearby.stream()
            .max(Comparator.comparingDouble((AbstractNPC other) ->
                    npc.getMemory().getImpression(other.getId())
                        .map(imp -> imp.getTrust())
                        .orElse(0.5))
                .thenComparing(AbstractNPC::getId))
            .orElse(null);
        if (listener == null) return null;

        // Save previous tick BEFORE overwriting — DialogTask will revert it
        // if the HTTP call fails, so the speaker's cooldown is not consumed on failure.
        long previousLastDialogTick = lastDialogTick;
        lastDialogTick = world.getCurrentTick();
        String prompt = DialogPromptBuilder.build(npc, listener, world);
        log.debug("[{}] Prepared dialog task with [{}]", npc.getId(), listener.getId());
        return new DialogTask(npc, listener, prompt, npc.getLLMClient(), previousLastDialogTick);
    }

    /**
     * Marks this NPC as having participated in a dialog at the given tick.
     * Called on the LISTENER by {@link DialogTask#applyResult} so that both
     * speaker and listener share the same global cooldown after a conversation.
     * Also used to REVERT the speaker's cooldown when an HTTP call fails — pass
     * the pre-Phase-1 tick value to undo the cooldown consumed in prepare().
     */
    public void markCompleted(long tick) {
        lastDialogTick = tick;
    }

    /**
     * Returns true for strategy types that suppress dialog entirely.
     *
     * This replaces the old numeric-threshold check
     * ({@code getActionMultiplier("Dialog") < 0.35}) which was misleading because
     * the Dialog multiplier is a binary gate here, not a continuous utility scaler
     * used by the DecisionEngine. Explicitly enumerating suppressed strategies makes
     * the intent unambiguous and decouples DialogAction from the multiplier API.
     */
    private static boolean isDialogSuppressedBy(Strategy strategy) {
        return switch (strategy.getType()) {
            case AVOID_CONFLICT, SURVIVE, GATHER_FOOD, RETALIATE -> true;
            default -> false;
        };
    }
}
