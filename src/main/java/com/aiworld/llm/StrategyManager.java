package com.aiworld.llm;

import com.aiworld.core.World;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

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
 * LLM calls are dispatched to a single background thread so they never block
 * the main simulation tick. The prompt is built synchronously (capturing a
 * consistent snapshot of NPC state), then the HTTP call runs off-thread.
 * The result is applied at the next tick once the Future completes.
 */
public class StrategyManager {

    private static final Logger log = LoggerFactory.getLogger(StrategyManager.class);

    private static final int    BASE_COOLDOWN         = 30;
    private static final int    COOLDOWN_JITTER       = 30;
    private static final int    EMERGENCY_LOCKOUT     = 8;
    private static final double FOOD_DANGER           = 0.15;

    private final LLMClient      llmClient;
    private final Random         random     = new Random();

    /** Daemon thread — one per NPC, so LLM calls never block the tick thread. */
    private final ExecutorService llmExecutor;

    // volatile: read by the HTTP server thread (StateSerializer) while written by the
    // world-loop thread (tick). Without volatile, the HTTP thread may see a stale value.
    private volatile Strategy currentStrategy;
    private Future<Strategy> pendingCall          = null;
    private boolean          pendingCallIsEmergency = false;

    private int  ticksUntilNextCall        = BASE_COOLDOWN;
    private long lastCallTick              = Long.MIN_VALUE; // MIN_VALUE = "before any tick"
    private long lastStarvationCallTick    = -EMERGENCY_LOCKOUT;
    private long lastConflictCallTick      = -EMERGENCY_LOCKOUT;
    // Updated at conflict-trigger dispatch time (not just at completion) so that
    // the same old attack events can't re-fire the conflict trigger every 8 ticks
    // indefinitely when LLM calls keep failing (lastCallTick stays MIN_VALUE).
    private long lastConflictEventTick     = Long.MIN_VALUE;

    public StrategyManager(LLMClient llmClient, String npcId) {
        this.llmClient          = llmClient;
        this.currentStrategy    = Strategy.defaultStrategy(0);  // eager init avoids getter side-effect
        this.ticksUntilNextCall = BASE_COOLDOWN + random.nextInt(COOLDOWN_JITTER);
        this.llmExecutor        = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-worker-" + npcId);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Per-tick entry point ──────────────────────────────────────────

    /**
     * Called once per tick from AbstractNPC.update(), before DecisionEngine runs.
     *
     * Order of operations:
     *  1. Guard: dead NPCs cancel any in-flight call and exit immediately
     *  2. Apply completed LLM result if available
     *  3. Always advance the cooldown countdown (decoupled from in-flight state)
     *  4. Check trigger conditions
     *  5. Dispatch new async LLM call if triggered (and no call already in-flight)
     */
    public void tick(AbstractNPC npc, World world) {
        // ── 1. Dead-NPC guard ─────────────────────────────────────────
        // AbstractNPC.update() returns early on death before calling tick(), so
        // this path is only reached in unusual circumstances (e.g., future
        // refactoring). Cancel any in-flight call to avoid wasting API quota.
        if (npc.getState().isDead()) {
            cancelPendingCall();
            return;
        }

        long now = world.getCurrentTick();

        // ── 2. Apply completed result ─────────────────────────────────
        if (pendingCall != null && pendingCall.isDone()) {
            try {
                Strategy result = pendingCall.get();
                if (result != null) {
                    this.currentStrategy = result;
                    log.info("[{}][Strategy] NEW: {} | Intent: {} | Reason: {}",
                        npc.getId(), result.getType(), result.getIntent(), result.getReason());
                } else {
                    log.warn("[{}][Strategy] LLM returned null — keeping: {}",
                        npc.getId(), currentStrategy != null ? currentStrategy.getType() : "none");
                }
            } catch (CancellationException e) {
                log.debug("[{}][Strategy] LLM call cancelled", npc.getId());
            } catch (ExecutionException e) {
                log.warn("[{}][Strategy] LLM call failed: {}", npc.getId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}][Strategy] LLM call interrupted", npc.getId());
            }
            pendingCall          = null;
            pendingCallIsEmergency = false;
            lastCallTick         = now;
            // [Issue 1] Reset the regular cooldown every time a call completes, regardless
            // of whether it was a routine or emergency call. Without this, a long-running
            // emergency call that spans the point where ticksUntilNextCall hits 0 will make
            // cooldownExpired=true on the very next tick, firing a redundant routine call
            // immediately after the fresh emergency strategy was just applied.
            ticksUntilNextCall = BASE_COOLDOWN + random.nextInt(COOLDOWN_JITTER);
        }

        // ── 3. Always advance countdown ───────────────────────────────
        // Decoupled from in-flight state: previously the countdown paused while a
        // call was in-flight, making the effective cooldown BASE + HTTP_LATENCY
        // rather than BASE. Now the countdown always advances; dispatch is simply
        // skipped (step 4) if a call is already in-flight.
        ticksUntilNextCall--;

        // ── 4. Handle in-flight calls ─────────────────────────────────
        // [Issue 3] If the NPC is stuck in RETALIATE but the aggressor has already died,
        // treat the cooldown as expired immediately so the strategy is re-evaluated and the
        // suppression of Gather/Rest/Move:FOOD is lifted without waiting up to 60 ticks.
        boolean retaliationStale = currentStrategy.getType() == Strategy.Type.RETALIATE
            && pendingCall == null
            && !isMostRecentConflictSourceAlive(npc, world);
        boolean cooldownExpired  = ticksUntilNextCall <= 0 || retaliationStale;
        boolean starvationRisk   = npc.getState().getFoodRatio() < FOOD_DANGER
                                   && (now - lastStarvationCallTick) >= EMERGENCY_LOCKOUT;
        boolean conflictDetected = hasConflictEventSinceLastCall(npc)
                                   && (now - lastConflictCallTick) >= EMERGENCY_LOCKOUT;

        if (pendingCall != null) {
            // Cancel a routine in-flight call when an emergency fires so the NPC
            // gets a fresh strategy promptly rather than waiting up to 20s for a
            // non-urgent response to complete.
            // Never cancel an already-in-flight emergency call: LLM latency can exceed
            // EMERGENCY_LOCKOUT ticks, so repeatedly cancelling the emergency call would
            // prevent the NPC from ever receiving a new strategy during crisis.
            if ((starvationRisk || conflictDetected) && !pendingCallIsEmergency) {
                cancelPendingCall();
                log.info("[{}][StrategyManager] Cancelled routine call — emergency overrides", npc.getId());
            } else {
                return; // let the in-flight call finish
            }
        }

        if (!cooldownExpired && !starvationRisk && !conflictDetected) return;

        // ── 5. Dispatch async call ────────────────────────────────────
        String triggerReason = resolveTriggerReason(cooldownExpired, starvationRisk, conflictDetected);
        log.info("[{}][StrategyManager] Dispatching LLM call ({})", npc.getId(), triggerReason);

        // Build the prompt synchronously while we still hold the world state snapshot
        final String prompt   = LLMPromptBuilder.build(npc, world);
        final long   callTick = now;

        // Regular cooldown resets only when it expired — emergency triggers don't
        // push out the regular re-evaluation cadence. This keeps the two timers
        // independent: routine re-evaluation every 30–60 idle ticks, emergency
        // re-evaluation whenever starvation/conflict demands it.
        if (cooldownExpired)  ticksUntilNextCall    = BASE_COOLDOWN + random.nextInt(COOLDOWN_JITTER);
        if (starvationRisk)   lastStarvationCallTick = now;
        if (conflictDetected) {
            lastConflictCallTick  = now;
            lastConflictEventTick = now; // advance baseline so these same events don't re-trigger
        }

        pendingCallIsEmergency = starvationRisk || conflictDetected;
        pendingCall = llmExecutor.submit(() -> llmClient.call(prompt, callTick));
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Cancels any in-flight LLM call without shutting down the executor.
     * Called when an NPC dies from starvation mid-tick so the HTTP call is
     * aborted promptly rather than completing and writing to a dead NPC's state.
     */
    public void cancelPendingCall() {
        if (pendingCall != null) {
            pendingCall.cancel(true);
            pendingCall          = null;
            pendingCallIsEmergency = false;
        }
    }

    /**
     * Shuts down the background LLM executor thread.
     * Must be called when this manager is being replaced (via
     * {@link com.aiworld.npc.AbstractNPC#setLLMClient}) to prevent thread leaks.
     */
    public void shutdown() {
        llmExecutor.shutdownNow();
    }

    // ── Accessor ─────────────────────────────────────────────────────

    /**
     * Returns the current strategy. Never returns null — initialized to
     * {@link Strategy#defaultStrategy(long)} in the constructor.
     */
    public Strategy getCurrentStrategy() {
        return currentStrategy;
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Returns true if the NPC was attacked or robbed since the last strategy was applied
     * OR since the last conflict-triggered dispatch (whichever is more recent).
     *
     * Using the more recent of the two baselines prevents the same old attack events
     * from re-firing the conflict trigger every 8 ticks when LLM calls keep failing
     * and lastCallTick stays at Long.MIN_VALUE.
     */
    private boolean hasConflictEventSinceLastCall(AbstractNPC npc) {
        long last = Math.max(lastCallTick, lastConflictEventTick);
        return npc.getMemory()
                   .getEventsOfType(MemoryEvent.EventType.WAS_ATTACKED)
                   .stream().anyMatch(e -> e.getTick() > last)
            || npc.getMemory()
                   .getEventsOfType(MemoryEvent.EventType.WAS_ROBBED)
                   .stream().anyMatch(e -> e.getTick() > last);
    }

    private String resolveTriggerReason(boolean cooldown, boolean starvation, boolean conflict) {
        if (starvation) return "starvation risk";
        if (conflict)   return "conflict detected";
        return "cooldown expired";
    }

    /**
     * [Issue 3] Returns true if the NPC's most recent attacker or robber is still alive
     * in the world. Used to detect a stale RETALIATE strategy so it can be shed early
     * instead of self-penalising the NPC for up to 60 ticks with no valid target to strike.
     */
    private boolean isMostRecentConflictSourceAlive(AbstractNPC npc, World world) {
        MemoryEvent latest = Stream.concat(
                npc.getMemory().getEventsOfType(MemoryEvent.EventType.WAS_ATTACKED).stream(),
                npc.getMemory().getEventsOfType(MemoryEvent.EventType.WAS_ROBBED).stream())
            .max(Comparator.comparingLong(MemoryEvent::getTick))
            .orElse(null);
        if (latest == null || latest.getTargetId() == null) return false;
        String aggressorId = latest.getTargetId();
        // [STR-1] Filter dead NPCs explicitly. world.getNpcs() returns the raw list which still
        // contains intra-tick dead NPCs (they are removed by removeIf at the end of Phase 1).
        // Without the isDead() guard, an aggressor killed earlier in the same tick's update loop
        // is still counted as alive here, delaying stale-RETALIATE detection by one tick and
        // leaving the NPC with distorted utility scores (Attack ×2.0) for an unreachable target.
        return world.getNpcs().stream()
            .anyMatch(n -> aggressorId.equals(n.getId()) && !n.getState().isDead());
    }
}
