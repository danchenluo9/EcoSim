package com.aiworld.core;

import com.aiworld.action.DialogTask;
import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * World is the authoritative simulation environment.
 *
 * It owns all NPCs and resources, advances the simulation tick by tick,
 * and provides spatial query APIs that NPCs and actions use to perceive
 * their environment.
 *
 * Design: World is intentionally NOT a singleton — multiple World instances
 * can coexist for parallel simulation branches (useful for AI look-ahead).
 */
public class World {

    private static final Logger log = LoggerFactory.getLogger(World.class);

    private static final int MAX_DEAD_DISPLAY          = 20; // cap on dead NPCs retained for the frontend
    private static final int MAX_DIALOG_TASKS_PER_TICK = 4;  // max concurrent LLM dialog calls per tick

    private final int width;
    private final int height;
    private long currentTick;

    private final List<AbstractNPC> npcs      = new ArrayList<>();
    private final List<AbstractNPC> deadNpcs  = new ArrayList<>();
    private final List<Resource>    resources = new ArrayList<>();

    public World(int width, int height) {
        this.width       = width;
        this.height      = height;
        this.currentTick = 0;
    }

    // ── Registration ─────────────────────────────────────────────────

    public void addNPC(AbstractNPC npc)       { npcs.add(npc); }
    public void addResource(Resource resource) { resources.add(resource); }

    // ── Tick ─────────────────────────────────────────────────────────

    /**
     * Advances the simulation by one tick.
     *
     * The tick is split into two synchronized phases to prevent the LLM
     * dialog HTTP calls from holding the world lock for up to 20 seconds:
     *
     *  Phase 1 (synchronized):
     *    1. Advance tick counter
     *    2. Regenerate resources
     *    3. Update each NPC (goals, strategy, action)
     *    4. Collect pending dialog tasks (prompts captured while state is fresh)
     *    5. Move newly-dead NPCs to deadNpcs; clean stale impressions
     *
     *  HTTP phase (no lock):
     *    6. Run all dialog HTTP calls concurrently — frontend can poll freely
     *
     *  Phase 3 (synchronized, brief):
     *    7. Apply dialog results (trust updates, memory events, conversation logs)
     */
    public void tick() {
        List<DialogTask> dialogTasks;

        // ── Phase 1: synchronized world update ───────────────────────
        synchronized (this) {
            currentTick++;
            log.info("═══ Tick {} ═══", currentTick);

            resources.forEach(Resource::tick);

            dialogTasks = new ArrayList<>();
            // Shuffle update order each tick so no NPC has a persistent first-mover advantage
            // in combat, food gathering, or dialog initiation.
            List<AbstractNPC> updateOrder = new ArrayList<>(npcs);
            Collections.shuffle(updateOrder, ThreadLocalRandom.current());
            // Update all NPCs first so dialog prompts capture the full post-update world state.
            // (AbstractNPC.update() Javadoc: "prepareDialogTask is called after all NPC updates")
            updateOrder.forEach(npc -> npc.update(this));
            // Cap dialog tasks per tick so allOf().join() can't block for N×20s when
            // many NPCs qualify simultaneously. updateOrder is already shuffled, so the
            // cap is applied fairly — no NPC has a persistent first-mover advantage.
            for (AbstractNPC npc : updateOrder) {
                if (dialogTasks.size() >= MAX_DIALOG_TASKS_PER_TICK) break;
                DialogTask task = npc.prepareDialogTask(this);
                if (task != null) dialogTasks.add(task);
            }

            // Separate newly-dead NPCs so spatial queries stay O(live)
            List<AbstractNPC> newlyDead = new ArrayList<>();
            npcs.removeIf(npc -> {
                if (npc.getState().isDead()) { newlyDead.add(npc); return true; }
                return false;
            });
            if (!newlyDead.isEmpty()) {
                deadNpcs.addAll(newlyDead);
                while (deadNpcs.size() > MAX_DEAD_DISPLAY) deadNpcs.remove(0);
                Set<String> newlyDeadIds = newlyDead.stream()
                    .map(AbstractNPC::getId).collect(Collectors.toSet());
                npcs.forEach(npc -> npc.getMemory().cleanImpressions(newlyDeadIds));
            }
        }

        // ── HTTP phase: dialog calls outside the world lock ───────────
        // The world lock is free here, so /api/state polls are never blocked
        // by a 20-second LLM timeout. All NPCs' calls run concurrently.
        if (!dialogTasks.isEmpty()) {
            CompletableFuture<?>[] futures = dialogTasks.stream()
                .map(task -> CompletableFuture.runAsync(task::executeCall, DialogTask.EXECUTOR))
                .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join(); // callRaw() never throws; join() is safe

            // ── Phase 3: apply results ────────────────────────────────
            synchronized (this) {
                dialogTasks.forEach(t -> t.applyResult(this));
            }
        }
    }

    // ── Spatial Queries (NPC perception API) ─────────────────────────

    /**
     * Returns all living NPCs within {@code radius} Manhattan distance of {@code center}.
     * Used by actions and perception for neighbor-awareness.
     *
     * The live list ({@code npcs}) is the primary performance guard — dead NPCs are
     * moved out at the end of each tick. The {@code !isDead()} filter below handles
     * intra-tick deaths: if NPC A kills NPC B earlier in the same tick, subsequent
     * updates within that tick won't see B as a valid interaction target.
     */
    public List<AbstractNPC> getNPCsNear(Location center, int radius) {
        return npcs.stream()
            .filter(npc -> !npc.getState().isDead())
            .filter(npc -> npc.getState().getLocation().distanceTo(center) <= radius)
            .collect(Collectors.toList());
    }

    /**
     * Returns all living NPCs within {@code radius} Manhattan distance of {@code caller},
     * excluding the caller itself.
     *
     * Prefer this overload over the Location variant whenever the calling NPC is
     * available — it prevents the common bug of a caller interacting with itself.
     */
    public List<AbstractNPC> getNPCsNear(AbstractNPC caller, int radius) {
        return getNPCsNear(caller.getState().getLocation(), radius).stream()
            .filter(npc -> !npc.getId().equals(caller.getId()))
            .collect(Collectors.toList());
    }

    /**
     * Returns the resource at exactly the given location, if any.
     * Used by GatherAction to check what's available underfoot.
     */
    public Optional<Resource> getResourceAt(Location location) {
        return resources.stream()
            .filter(r -> r.getLocation().equals(location) && !r.isDepleted())
            .findFirst();
    }

    /**
     * Returns the location of the nearest non-depleted resource of given type.
     * Used by DecisionEngine to guide foraging movement.
     */
    public Location getNearestResourceLocation(Location from, Resource.Type type) {
        return resources.stream()
            .filter(r -> r.getType() == type && !r.isDepleted())
            .min(Comparator.comparingInt(r -> r.getLocation().distanceTo(from)))
            .map(Resource::getLocation)
            .orElse(null);
    }

    /**
     * Returns the location of the nearest NPC that the given NPC trusts highly.
     * Used by DecisionEngine to guide social movement.
     */
    public Location getNearestTrustedAllyLocation(AbstractNPC seeker) {
        return npcs.stream()
            .filter(other -> !other.getState().isDead())  // intra-tick death guard (mirrors getNPCsNear)
            .filter(other -> !other.getId().equals(seeker.getId()))
            .filter(other -> seeker.getMemory()
                .getImpression(other.getId())
                .map(imp -> imp.getTrust() > 0.65)
                .orElse(false))
            .min(Comparator.comparingInt(other ->
                other.getState().getLocation()
                     .distanceTo(seeker.getState().getLocation())))
            .map(other -> other.getState().getLocation())
            .orElse(null);
    }

    // ── Accessors ────────────────────────────────────────────────────

    public int               getWidth()       { return width; }
    public int               getHeight()      { return height; }
    public long              getCurrentTick() { return currentTick; }
    /** Returns only live NPCs. Use {@link #getDeadNpcs()} for the dead list. */
    public List<AbstractNPC> getNpcs()        { return Collections.unmodifiableList(npcs); }
    public List<AbstractNPC> getDeadNpcs()    { return Collections.unmodifiableList(deadNpcs); }
    public List<Resource>    getResources()   { return Collections.unmodifiableList(resources); }
}
