package com.aiworld.llm;

import com.aiworld.core.World;
import com.aiworld.model.Location;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.NPC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests when StrategyManager decides to call the LLM: cooldown, starvation,
 * conflict, the 8-tick emergency lockout, and emergency-over-routine cancellation.
 *
 * Determinism comes from three test doubles:
 *  - ZeroJitterRandom   : cooldown is always exactly 30 ticks
 *  - DirectExecutor     : LLM calls run synchronously on the test thread
 *  - ControllableWorld  : the test sets the current tick directly
 */
class StrategyManagerTriggerTest {

    // ── Test doubles ──────────────────────────────────────────────────

    /** Removes the 0–29 tick jitter so the cooldown is exactly BASE_COOLDOWN (30). */
    static class ZeroJitterRandom extends Random {
        @Override public int nextInt(int bound) { return 0; }
    }

    /** Runs submitted tasks inline, so the Future is already done when submit() returns. */
    static class DirectExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;
        @Override public void execute(Runnable r)          { r.run(); }
        @Override public void shutdown()                   { shutdown = true; }
        @Override public List<Runnable> shutdownNow()      { shutdown = true; return List.of(); }
        @Override public boolean isShutdown()              { return shutdown; }
        @Override public boolean isTerminated()            { return shutdown; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** World whose tick counter is set by the test instead of by World.tick(). */
    static class ControllableWorld extends World {
        long tick;
        ControllableWorld() { super(20, 20); }
        @Override public long getCurrentTick() { return tick; }
    }

    /** Records the tick of every LLM call and returns a fixed strategy type. */
    static class RecordingClient implements LLMClient {
        final List<Long> callTicks = new CopyOnWriteArrayList<>();
        volatile Strategy.Type returnType = Strategy.Type.GATHER_FOOD;
        @Override public Strategy call(String prompt, long tick) {
            callTicks.add(tick);
            return new Strategy(returnType, "intent", "reason", tick);
        }
        @Override public String callRaw(String prompt) { return null; }
    }

    // ── Fixture ───────────────────────────────────────────────────────

    private ControllableWorld world;
    private NPC npc;
    private RecordingClient client;
    private StrategyManager manager;

    @BeforeEach
    void setUp() {
        world   = new ControllableWorld();
        npc     = new NPC("alice", new Location(5, 5));     // starts at food 70/100
        client  = new RecordingClient();
        manager = new StrategyManager(client, new ZeroJitterRandom(), new DirectExecutor());
    }

    /** Advances the world one tick at a time through [from, to] and ticks the manager. */
    private void runTicks(long from, long to) {
        for (long t = from; t <= to; t++) {
            world.tick = t;
            manager.tick(npc, world);
        }
    }

    private void starve()  { npc.getState().depleteFood(npc.getState().getFood() - 10); } // 10% < 15%
    private void attackedAt(long tick, String attackerId) {
        npc.getMemory().addEvent(new MemoryEvent(tick, MemoryEvent.EventType.WAS_ATTACKED,
            "attacked by " + attackerId, new Location(5, 5), -0.8, attackerId));
    }

    // ── Cooldown ──────────────────────────────────────────────────────

    @Test
    void noCallBeforeCooldownExpires() {
        runTicks(1, 29);
        assertTrue(client.callTicks.isEmpty());
    }

    @Test
    void routineCallFiresWhenCooldownExpiresAndRepeatsEvery30Ticks() {
        runTicks(1, 95);
        assertEquals(List.of(30L, 60L, 90L), client.callTicks);
    }

    @Test
    void appliedResultReplacesCurrentStrategyOnTheNextTick() {
        client.returnType = Strategy.Type.SEEK_ALLIES;     // default strategy is EXPLORE
        runTicks(1, 30);                                   // dispatched at 30
        assertEquals(Strategy.Type.EXPLORE, manager.getCurrentStrategy().getType());
        runTicks(31, 31);                                  // applied at 31
        assertEquals(Strategy.Type.SEEK_ALLIES, manager.getCurrentStrategy().getType());
    }

    // ── Starvation trigger + lockout ──────────────────────────────────

    @Test
    void starvationTriggersImmediatelyAndRespectsTheEightTickLockout() {
        starve();
        runTicks(1, 20);
        assertEquals(List.of(1L, 9L, 17L), client.callTicks,
            "starvation should fire at once, then at most once every 8 ticks");
    }

    // ── Conflict trigger ──────────────────────────────────────────────

    @Test
    void conflictTriggersOncePerNewAttackNotRepeatedlyForTheSameOne() {
        attackedAt(5, "bob");
        runTicks(6, 25);
        assertEquals(List.of(6L), client.callTicks, "an old attack must not re-fire the trigger");

        attackedAt(26, "bob");
        runTicks(27, 27);
        assertEquals(List.of(6L, 27L), client.callTicks, "a new attack after the lockout fires again");
    }

    /**
     * Regression test: an attack that happens after the prompt was built (while the call
     * is in flight or in the same tick its result is applied) was never seen by the LLM,
     * so it must still trigger a conflict call once the lockout ends.
     */
    @Test
    void attackDuringLockoutIsDeferredUntilLockoutEnds() {
        attackedAt(5, "bob");
        runTicks(6, 6);                 // conflict call at 6
        attackedAt(7, "bob");           // new attack inside the 8-tick lockout
        runTicks(7, 13);
        assertEquals(List.of(6L), client.callTicks);
        runTicks(14, 14);               // 14 - 6 = 8 -> lockout over
        assertEquals(List.of(6L, 14L), client.callTicks);
    }

    @Test
    void staleRetaliateStrategyIsReEvaluatedWhenAttackerIsGone() {
        client.returnType = Strategy.Type.RETALIATE;
        attackedAt(5, "bob");           // "bob" is not in the world -> not alive
        runTicks(6, 6);                 // conflict call returns RETALIATE
        client.returnType = Strategy.Type.GATHER_FOOD;
        runTicks(7, 7);                 // RETALIATE applied, attacker absent -> immediate re-call
        assertEquals(List.of(6L, 7L), client.callTicks);
    }

    // ── Dead NPC ──────────────────────────────────────────────────────

    @Test
    void deadNpcNeverCallsTheLlm() {
        starve();
        npc.getState().damageHealth(npc.getState().getHealth());
        runTicks(1, 40);
        assertTrue(client.callTicks.isEmpty());
    }

    // ── Emergency vs. in-flight calls (real background thread) ───────

    /** Client whose first call blocks until released; records whether it was interrupted. */
    static class BlockingFirstCallClient implements LLMClient {
        final CountDownLatch firstCallStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstCall = new CountDownLatch(1);
        final CountDownLatch secondCallMade   = new CountDownLatch(1);
        final AtomicBoolean  firstCallInterrupted = new AtomicBoolean(false);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override public Strategy call(String prompt, long tick) {
            if (first.getAndSet(false)) {
                firstCallStarted.countDown();
                try {
                    releaseFirstCall.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    firstCallInterrupted.set(true);
                    return null;
                }
                return new Strategy(Strategy.Type.EXPLORE, "i", "r", tick);
            }
            secondCallMade.countDown();
            return new Strategy(Strategy.Type.SURVIVE, "i", "r", tick);
        }
        @Override public String callRaw(String prompt) { return null; }
    }

    @Test
    void emergencyCancelsAnInFlightRoutineCall() throws Exception {
        BlockingFirstCallClient blocking = new BlockingFirstCallClient();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        manager = new StrategyManager(blocking, new ZeroJitterRandom(), exec);
        try {
            runTicks(1, 30);                                     // routine call dispatched, now blocking
            assertTrue(blocking.firstCallStarted.await(5, TimeUnit.SECONDS));

            starve();
            runTicks(31, 31);                                    // emergency arrives

            assertTrue(blocking.secondCallMade.await(5, TimeUnit.SECONDS), "emergency call should be dispatched");
            assertTrue(blocking.firstCallInterrupted.get(), "routine call should have been cancelled");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void emergencyDoesNotCancelAnInFlightEmergencyCall() throws Exception {
        BlockingFirstCallClient blocking = new BlockingFirstCallClient();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        manager = new StrategyManager(blocking, new ZeroJitterRandom(), exec);
        try {
            starve();
            runTicks(1, 1);                                      // emergency call, now blocking
            assertTrue(blocking.firstCallStarted.await(5, TimeUnit.SECONDS));

            attackedAt(2, "bob");
            runTicks(3, 12);                                     // conflict fires while emergency in flight

            assertFalse(blocking.firstCallInterrupted.get(), "in-flight emergency call must not be cancelled");
            assertFalse(blocking.secondCallMade.await(200, TimeUnit.MILLISECONDS), "no second call while one is in flight");
        } finally {
            blocking.releaseFirstCall.countDown();
            exec.shutdownNow();
        }
    }
}
