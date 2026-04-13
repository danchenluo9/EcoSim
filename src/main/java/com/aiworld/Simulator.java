package com.aiworld;

import com.aiworld.core.World;
import com.aiworld.core.WorldLoop;
import com.aiworld.llm.ClaudeClient;
import com.aiworld.llm.LLMClient;
import com.aiworld.llm.PlaybackLLMClient;
import com.aiworld.llm.RecordingLLMClient;
import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.NPC;
import com.aiworld.server.WorldServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulator — application entry point.
 *
 * Bootstraps the world with NPCs and resources, then hands control
 * to the WorldLoop. Edit this class to configure scenarios:
 *   - Resource-scarce world → fierce competition / hostile NPC clusters
 *   - Abundant world        → cooperation, division of labor, group migration
 *   - Mixed archetypes      → emergent social hierarchies
 */
public class Simulator {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    // ── Simulation parameters (override via environment variables) ────
    private static final int  WORLD_WIDTH      = envInt ("ECOSIM_WIDTH",    20);
    private static final int  WORLD_HEIGHT     = envInt ("ECOSIM_HEIGHT",   20);
    private static final long TICK_INTERVAL_MS = envLong("ECOSIM_TICK_MS",  300);  // ~3 tps
    private static final long MAX_TICKS        = envLong("ECOSIM_MAX_TICKS",100);  // 0 = run forever

    private static int  envInt (String name, int  def) { String v = System.getenv(name); return v != null ? Integer.parseInt(v)  : def; }
    private static long envLong(String name, long def) { String v = System.getenv(name); return v != null ? Long.parseLong(v)    : def; }

    /**
     * Creates the LLM client for an NPC based on ECOSIM_LLM_MODE:
     *   (unset)  — live Claude API
     *   record   — live Claude API + saves responses to mock-data/
     *   playback — replays responses from mock-data/, no API calls
     */
    private static LLMClient createLLMClient(String npcName) {
        String mode = System.getenv("ECOSIM_LLM_MODE");
        if ("playback".equalsIgnoreCase(mode)) {
            return new PlaybackLLMClient(npcName, "mock-data");
        }
        ClaudeClient live = ClaudeClient.fromEnv();
        if ("record".equalsIgnoreCase(mode)) {
            return new RecordingLLMClient(live, npcName, "mock-data");
        }
        return live;
    }

    public static void main(String[] args) throws Exception {

        log.info("╔══════════════════════════════════╗");
        log.info("║   AI NPC Virtual World System    ║");
        log.info("╚══════════════════════════════════╝");

        // ── 1. Create world ──────────────────────────────────────────
        World world = new World(WORLD_WIDTH, WORLD_HEIGHT);

        // ── 2. Place resources ───────────────────────────────────────
        // Three food clusters — creates distinct territorial zones
        world.addResource(new Resource(Resource.Type.FOOD,
            new Location(3,  3),  50, 100, 2));
        world.addResource(new Resource(Resource.Type.FOOD,
            new Location(15, 5),  40, 100, 2));
        world.addResource(new Resource(Resource.Type.FOOD,
            new Location(10, 15), 60, 100, 3));
        world.addResource(new Resource(Resource.Type.MEDICINE,
            new Location(10, 10), 30,  50, 1));

        // ── 3. Spawn NPCs ────────────────────────────────────────────
        // Each NPC gets its own LLM client so circuit-breaker faults are isolated
        // per-NPC rather than blocking all LLM calls when one NPC's prompt fails.
        NPC alice = new NPC("Alice", new Location(2,  2));  alice.setLLMClient(createLLMClient("Alice"));
        NPC bob   = new NPC("Bob",   new Location(14, 4));  bob.setLLMClient(createLLMClient("Bob"));
        NPC carol = new NPC("Carol", new Location(9,  14)); carol.setLLMClient(createLLMClient("Carol"));
        NPC dave  = new NPC("Dave",  new Location(5,  10)); dave.setLLMClient(createLLMClient("Dave"));
        NPC eve   = new NPC("Eve",   new Location(18, 18)); eve.setLLMClient(createLLMClient("Eve"));

        world.addNPC(alice);
        world.addNPC(bob);
        world.addNPC(carol);
        world.addNPC(dave);
        world.addNPC(eve);

        // ── 4. Create the simulation loop (not started yet) ──────────
        WorldLoop loop = new WorldLoop(world, TICK_INTERVAL_MS, MAX_TICKS);

        // ── 5. Start the HTTP server ──────────────────────────────────
        WorldServer server = new WorldServer(loop);
        try {
            server.start();
        } catch (Exception e) {
            log.error("Failed to start WorldServer — cannot continue without UI: {}", e.getMessage());
            return;  // nothing can start the loop without the server, so halt cleanly
        }

        // Shut the server down cleanly on Ctrl+C or any JVM exit signal.
        // The server is NOT stopped when the simulation loop ends — it stays
        // alive so the browser can continue polling /api/state and display
        // the final world state. The user kills the process when done viewing.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server and executors.");
            server.stop();
            loop.stop();              // shuts down world-loop scheduler + per-NPC LLM threads + dialog pool
        }, "shutdown-hook"));

        log.info("Ready — open http://localhost:5173 to configure and start the simulation.");

        // ── 6. Wait for the web UI to start the loop, then wait for it to finish ──
        // Cap the wait at 30 minutes; if the user never clicks Start in the UI
        // the process would otherwise park the main thread indefinitely.
        long startDeadlineMs = System.currentTimeMillis() + 30L * 60 * 1000;
        while (!loop.isRunning()) {
            if (System.currentTimeMillis() > startDeadlineMs) {
                log.warn("Simulation was never started via the UI after 30 minutes — exiting.");
                return;
            }
            Thread.sleep(200);
        }
        while (loop.isRunning()) {
            Thread.sleep(200);
        }

        // ── 7. Print final world state ───────────────────────────────
        log.info("══ Final World State ══");
        world.getNpcs().forEach(npc -> log.info("  {}", npc));
        world.getDeadNpcs().forEach(npc -> log.info("  {} [DEAD]", npc));
        log.info("Simulation complete. Server still running — press Ctrl+C to exit.");
    }
}
