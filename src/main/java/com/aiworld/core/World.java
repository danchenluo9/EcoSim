package com.aiworld.core;

import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;

import java.util.*;
import java.util.stream.Collectors;

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

    private final int width;
    private final int height;
    private long currentTick;

    private final List<AbstractNPC> npcs      = new ArrayList<>();
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
     * Order of operations:
     *  1. Regenerate resources
     *  2. Update each NPC (they perceive, decide, and act)
     *  3. Remove dead NPCs
     *  4. Advance tick counter
     */
    public void tick() {
        currentTick++;
        System.out.println("\n═══ Tick " + currentTick + " ═══");

        resources.forEach(Resource::tick);          // 1. regen
        npcs.forEach(npc -> npc.update(this));      // 2. NPC updates
        npcs.removeIf(npc -> {                      // 3. reap dead
            if (npc.getState().isDead()) {
                System.out.printf("[%s] has died at tick %d%n",
                    npc.getId(), currentTick);
                return true;
            }
            return false;
        });
    }

    // ── Spatial Queries (NPC perception API) ─────────────────────────

    /**
     * Returns all NPCs within {@code radius} Manhattan distance of {@code center}.
     * Used by actions and perception for neighbor-awareness.
     */
    public List<AbstractNPC> getNPCsNear(Location center, int radius) {
        return npcs.stream()
            .filter(npc -> npc.getState().getLocation().distanceTo(center) <= radius)
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
    public List<AbstractNPC> getNpcs()        { return Collections.unmodifiableList(npcs); }
    public List<Resource>    getResources()   { return Collections.unmodifiableList(resources); }
}
