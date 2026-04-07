package com.aiworld.core;

import com.aiworld.action.DialogTask;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WorldLoop drives the simulation at a fixed tick rate using a
 * scheduled executor. This keeps the simulation decoupled from wall-clock
 * time — tick rate can be accelerated for fast-forward or slowed for
 * real-time visualization.
 *
 * Extension: replace with a variable-rate loop (e.g., when all NPCs
 * are resting, skip forward until the next interesting event).
 */
public class WorldLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WorldLoop.class);

    private final World                 world;
    private final long                  tickIntervalMs;
    private final long                  maxTicks;        // 0 = infinite

    private ScheduledExecutorService    executor;
    private ScheduledFuture<?>          future;
    private volatile boolean            running = false;
    private volatile boolean            paused  = false;

    /**
     * @param world           the world to simulate
     * @param tickIntervalMs  milliseconds between each tick
     * @param maxTicks        stop after this many ticks (0 = run forever)
     */
    public WorldLoop(World world, long tickIntervalMs, long maxTicks) {
        this.world          = world;
        this.tickIntervalMs = tickIntervalMs;
        this.maxTicks       = maxTicks;
    }

    /** Starts the simulation loop on a background thread. */
    public synchronized void start() {
        if (running) throw new IllegalStateException("Loop already running");
        running  = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "world-loop");
            t.setDaemon(true);
            return t;
        });
        future = executor.scheduleAtFixedRate(
            this, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        log.info("WorldLoop started — tick rate: {}", String.format("%.2f tps", 1000.0 / tickIntervalMs));
    }

    /**
     * Stops the loop gracefully, waiting up to 2 seconds for the current tick to finish.
     * Also shuts down per-NPC LLM executor threads so the JVM can exit cleanly.
     */
    public synchronized void stop() {
        running = false;
        if (future   != null) future.cancel(false);
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Shut down per-NPC LLM strategy executor threads (both live and dead NPCs).
        // These are daemon threads so the JVM would exit anyway, but explicit shutdown
        // avoids thread leaks when multiple simulation runs share a JVM (e.g., tests).
        shutdownNpcExecutors(world.getNpcs());
        shutdownNpcExecutors(world.getDeadNpcs());
        log.info("WorldLoop stopped at tick {}", world.getCurrentTick());
    }

    private void shutdownNpcExecutors(List<AbstractNPC> npcs) {
        npcs.forEach(npc -> {
            if (npc.getStrategyManager() != null) {
                npc.getStrategyManager().shutdown();
            }
        });
    }

    /** Pauses tick execution without stopping the executor thread. */
    public void pause() {
        paused = true;
        log.info("WorldLoop paused at tick {}", world.getCurrentTick());
    }

    /** Resumes tick execution after a pause. */
    public void resume() {
        paused = false;
        log.info("WorldLoop resumed at tick {}", world.getCurrentTick());
    }

    public boolean isPaused() { return paused; }

    @Override
    public void run() {
        if (!running || paused) return;
        try {
            world.tick();
            if (maxTicks > 0 && world.getCurrentTick() >= maxTicks) {
                log.info("Max ticks reached — stopping simulation.");
                // Set flag and cancel future invocations directly.
                // Calling stop() here would invoke awaitTermination() from inside the
                // executor's own thread, blocking for 2 seconds before timing out.
                running = false;
                if (future != null) future.cancel(false);
                // Shut down per-NPC LLM executors and the shared dialog executor.
                // stop() won't be called from this path (deadlock risk on awaitTermination),
                // so both shutdown steps must be done here explicitly.
                shutdownNpcExecutors(world.getNpcs());
                shutdownNpcExecutors(world.getDeadNpcs());
                DialogTask.shutdownExecutor();
            }
        } catch (Exception e) {
            log.error("Error during tick {}", world.getCurrentTick(), e);
        }
    }

    public boolean isRunning() { return running; }
    public World   getWorld()  { return world; }
}
