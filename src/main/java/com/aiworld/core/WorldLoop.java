package com.aiworld.core;

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

    private final World                 world;
    private final long                  tickIntervalMs;
    private final long                  maxTicks;        // 0 = infinite

    private ScheduledExecutorService    executor;
    private ScheduledFuture<?>          future;
    private volatile boolean            running = false;

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
    public void start() {
        if (running) throw new IllegalStateException("Loop already running");
        running  = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "world-loop");
            t.setDaemon(true);
            return t;
        });
        future = executor.scheduleAtFixedRate(
            this, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        System.out.println("WorldLoop started — tick rate: "
            + (1000 / tickIntervalMs) + " tps");
    }

    /** Stops the loop gracefully, waiting up to 2 seconds for the current tick to finish. */
    public void stop() {
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
        System.out.println("WorldLoop stopped at tick " + world.getCurrentTick());
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            world.tick();
            if (maxTicks > 0 && world.getCurrentTick() >= maxTicks) {
                System.out.println("Max ticks reached — stopping simulation.");
                stop();
            }
        } catch (Exception e) {
            System.err.println("Error during tick " + world.getCurrentTick()
                + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isRunning() { return running; }
}
