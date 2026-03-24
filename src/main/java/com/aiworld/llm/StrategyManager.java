package com.aiworld.llm;

import com.aiworld.core.World;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;

import java.util.Random;

/**
 * Manages LLM-driven strategic planning for a single NPC.
 *
 * Holds the NPC's current {@link Strategy} and decides when to request a new
 * one from the LLM. The existing DecisionEngine continues running every tick;
 * StrategyManager only calls the LLM when trigger conditions are met.
 *
 * Trigger conditions (any one is sufficient):
 *  1. Cooldown expired  — every 30–60 ticks (jittered so all NPCs don't call at once)
 *  2. Starvation risk   — food ratio falls below 15 %
 *  3. Conflict detected — WAS_ATTACKED event in memory since last LLM call
 *
 * "No clear goal" (urgency < 0.2) is noted in the trigger reason when the cooldown
 * fires, but does NOT independently bypass the cooldown — otherwise it triggers
 * on every tick at simulation start before goals have built up.
 *
 * If the LLM call fails (timeout, bad JSON, network error), the current
 * strategy is kept unchanged and the cooldown is reset to avoid hammering
 * a failing endpoint.
 */
public class StrategyManager {

    private static final int    BASE_COOLDOWN   = 30;  // minimum ticks between LLM calls
    private static final int    COOLDOWN_JITTER = 30;  // adds 0–29 random ticks to spread load
    private static final double FOOD_DANGER     = 0.15;
    private static final double GOAL_DRIFT      = 0.20;

    private final LLMClient llmClient;
    private final Random    random = new Random();

    private Strategy currentStrategy;
    private int      ticksUntilNextCall;
    private long     lastCallTick = 0;

    public StrategyManager(LLMClient llmClient) {
        this.llmClient         = llmClient;
        this.ticksUntilNextCall = BASE_COOLDOWN + random.nextInt(COOLDOWN_JITTER);
    }

    // ── Per-tick entry point ──────────────────────────────────────────

    /**
     * Called once per tick (from AbstractNPC.update) before DecisionEngine runs.
     * Checks trigger conditions and fires an LLM call if needed.
     */
    public void tick(AbstractNPC npc, World world) {
        ticksUntilNextCall--;

        boolean cooldownExpired  = ticksUntilNextCall <= 0;
        boolean starvationRisk   = npc.getState().getFoodRatio() < FOOD_DANGER;
        boolean conflictDetected = wasAttackedSinceLastCall(npc, world.getCurrentTick());
        boolean noGoal           = maxGoalUrgency(npc) < GOAL_DRIFT;  // logged, not a bypass trigger

        if (cooldownExpired || starvationRisk || conflictDetected) {
            String triggerReason = resolveTriggerReason(
                cooldownExpired, starvationRisk, conflictDetected, noGoal);

            System.out.printf("[%s][StrategyManager] Triggering LLM call (%s)%n",
                npc.getId(), triggerReason);

            callLLM(npc, world);
            resetCooldown();
            lastCallTick = world.getCurrentTick();
        }
    }

    // ── LLM call ─────────────────────────────────────────────────────

    private void callLLM(AbstractNPC npc, World world) {
        String prompt = LLMPromptBuilder.build(npc, world);
        Strategy newStrategy = llmClient.call(prompt, world.getCurrentTick());

        if (newStrategy != null) {
            this.currentStrategy = newStrategy;
            System.out.printf(
                "[%s][Strategy] NEW: %-16s | Intent: %s | Reason: %s%n",
                npc.getId(),
                newStrategy.getType(),
                newStrategy.getIntent(),
                newStrategy.getReason()
            );
        } else {
            System.out.printf(
                "[%s][Strategy] LLM call failed — keeping current strategy: %s%n",
                npc.getId(),
                currentStrategy != null ? currentStrategy.getType() : "none"
            );
        }
    }

    // ── Accessor ─────────────────────────────────────────────────────

    /**
     * Returns the current strategy, or a default EXPLORE strategy if none
     * has been set yet. Never returns null.
     */
    public Strategy getCurrentStrategy() {
        if (currentStrategy == null) {
            currentStrategy = Strategy.defaultStrategy(0);
        }
        return currentStrategy;
    }

    // ── Private helpers ───────────────────────────────────────────────

    private void resetCooldown() {
        ticksUntilNextCall = BASE_COOLDOWN + random.nextInt(COOLDOWN_JITTER);
    }

    private double maxGoalUrgency(AbstractNPC npc) {
        return npc.getGoalSystem().getGoals().stream()
            .mapToDouble(g -> g.computeUrgency(npc.getState()))
            .max()
            .orElse(0.0);
    }

    private boolean wasAttackedSinceLastCall(AbstractNPC npc, long currentTick) {
        return npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.WAS_ATTACKED)
            .stream()
            .anyMatch(e -> e.getTick() > lastCallTick);
    }

    private String resolveTriggerReason(boolean cooldown, boolean starvation,
                                         boolean conflict, boolean noGoal) {
        if (starvation) return "starvation risk";
        if (conflict)   return "conflict detected";
        if (noGoal)     return "no clear goal";
        return "cooldown expired";
    }
}
