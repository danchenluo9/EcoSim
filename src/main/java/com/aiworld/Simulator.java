package com.aiworld;

import com.aiworld.core.World;
import com.aiworld.core.WorldLoop;
import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.NPC;

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

    // ── Simulation parameters ─────────────────────────────────────────
    private static final int  WORLD_WIDTH      = 20;
    private static final int  WORLD_HEIGHT     = 20;
    private static final long TICK_INTERVAL_MS = 300;  // ~3 ticks/sec
    private static final long MAX_TICKS        = 100;  // 0 = run forever

    public static void main(String[] args) throws InterruptedException {

        System.out.println("╔══════════════════════════════════╗");
        System.out.println("║   AI NPC Virtual World System    ║");
        System.out.println("╚══════════════════════════════════╝");

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
        world.addNPC(new NPC("Alice", new Location(2,  2)));
        world.addNPC(new NPC("Bob",   new Location(14, 4)));
        world.addNPC(new NPC("Carol", new Location(9,  14)));
        world.addNPC(new NPC("Dave",  new Location(5,  10)));
        world.addNPC(new NPC("Eve",   new Location(18, 18)));

        // ── 4. Start the simulation loop ─────────────────────────────
        WorldLoop loop = new WorldLoop(world, TICK_INTERVAL_MS, MAX_TICKS);
        loop.start();

        // ── 5. Block main thread until simulation ends ───────────────
        while (loop.isRunning()) {
            Thread.sleep(200);
        }

        // ── 6. Print final world state ───────────────────────────────
        System.out.println("\n══ Final World State ══");
        world.getNpcs().forEach(npc -> System.out.println("  " + npc));
        System.out.println("Simulation complete.");
    }
}
