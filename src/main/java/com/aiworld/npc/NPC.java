package com.aiworld.npc;

import com.aiworld.core.World;
import com.aiworld.goal.AggressionGoal;
import com.aiworld.goal.ExploreGoal;
import com.aiworld.goal.SocialGoal;
import com.aiworld.goal.SurvivalGoal;
import com.aiworld.model.Location;
import com.aiworld.model.MemoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * NPC — the general-purpose agent with configurable personality archetype.
 *
 * Use the static factory methods to create archetypes with preset goal weights:
 * <pre>
 *   NPC.create("Alice", loc)            // Default: balanced
 *   NPC.forager("Bob", loc)             // High survival, low social
 *   NPC.diplomat("Carol", loc)          // High social, low survival
 *   NPC.explorer("Dave", loc)           // High explore, moderate survival
 *   NPC.fighter("Eve", loc)             // High aggression, low social
 * </pre>
 *
 * Archetype can also be changed at runtime via {@link #reconfigureGoals(String)}
 * before the simulation starts (used by the setup UI).
 */
public class NPC extends AbstractNPC {

    private static final Logger log = LoggerFactory.getLogger(NPC.class);

    private static final int MET_NPC_THROTTLE_TICKS = 10;
    private final Map<String, Long> lastMetNpcTick = new HashMap<>();

    private String archetype;

    // ── Constructors ──────────────────────────────────────────────────

    /** Creates a Default-archetype NPC (balanced goal weights). */
    public NPC(String id, Location startLocation) {
        this(id, startLocation, "Default");
    }

    private NPC(String id, Location startLocation, String archetype) {
        super(id, new NPCState(80, 70, 100, startLocation),
              buildGoalSystem(archetype), new Memory());
        this.archetype = archetype;
    }

    // ── Factory methods ───────────────────────────────────────────────

    /** Survival 0.9 — food-obsessed, rarely socialises. */
    public static NPC forager(String id, Location loc)  { return new NPC(id, loc, "Forager");  }
    /** Social 0.9 — alliance builder, trades and talks constantly. */
    public static NPC diplomat(String id, Location loc) { return new NPC(id, loc, "Diplomat"); }
    /** Explore 0.9 — roams widely, discovers new territory. */
    public static NPC explorer(String id, Location loc) { return new NPC(id, loc, "Explorer"); }
    /** Aggression 0.8 — hunts others, barely builds trust. */
    public static NPC fighter(String id, Location loc)  { return new NPC(id, loc, "Fighter");  }

    // ── Runtime reconfiguration (UI setup only) ───────────────────────

    /**
     * Replaces the NPC's goal system with one matching the named archetype.
     * Only safe to call before the simulation starts.
     *
     * @param newArchetype one of: Default, Forager, Diplomat, Explorer, Fighter
     */
    public void reconfigureGoals(String newArchetype) {
        goalSystem    = buildGoalSystem(newArchetype);
        this.archetype = capitalize(newArchetype);
    }

    // ── Goal system builder ───────────────────────────────────────────

    private static GoalSystem buildGoalSystem(String archetype) {
        GoalSystem gs = new GoalSystem();
        switch (archetype.toLowerCase()) {
            case "forager":
                gs.addGoal(new SurvivalGoal(0.9));
                gs.addGoal(new ExploreGoal(0.2));
                gs.addGoal(new SocialGoal(0.1));
                break;
            case "diplomat":
                gs.addGoal(new SurvivalGoal(0.4));
                gs.addGoal(new ExploreGoal(0.2));
                gs.addGoal(new SocialGoal(0.9));
                break;
            case "explorer":
                gs.addGoal(new SurvivalGoal(0.5));
                gs.addGoal(new ExploreGoal(0.9));
                gs.addGoal(new SocialGoal(0.3));
                break;
            case "fighter":
                gs.addGoal(new SurvivalGoal(0.5));
                gs.addGoal(new ExploreGoal(0.4));
                gs.addGoal(new SocialGoal(0.05));
                gs.addGoal(new AggressionGoal(0.8));
                break;
            default: // "default"
                gs.addGoal(new SurvivalGoal(0.7));
                gs.addGoal(new ExploreGoal(0.4));
                gs.addGoal(new SocialGoal(0.3));
        }
        return gs;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Default";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ── AbstractNPC implementation ────────────────────────────────────

    @Override
    public String getArchetype() { return archetype; }

    /**
     * Perception phase: scan surroundings and record notable observations
     * as memory events before deciding on an action.
     */
    @Override
    protected void onPreUpdate(World world) {
        long now = world.getCurrentTick();
        // Prune expired entries — once the throttle window has passed the entry
        // serves no purpose and can be removed to keep the map bounded.
        lastMetNpcTick.entrySet().removeIf(e -> now - e.getValue() >= MET_NPC_THROTTLE_TICKS);

        world.getNPCsNear(this, 3).forEach(other -> {
                long lastSeen = lastMetNpcTick.getOrDefault(other.getId(), -MET_NPC_THROTTLE_TICKS - 1L);
                if (now - lastSeen >= MET_NPC_THROTTLE_TICKS) {
                    memory.addEvent(new MemoryEvent(
                        now,
                        MemoryEvent.EventType.MET_NPC,
                        "Spotted " + other.getId() + " nearby",
                        other.getState().getLocation(),
                        0.0, other.getId()
                    ));
                    lastMetNpcTick.put(other.getId(), now);
                }
            });
    }

    /** Post-action status log — warn if health is critical. */
    @Override
    protected void onPostUpdate(World world) {
        if (state.getHealth() <= 20) {
            log.warn("[{}] critically low health ({})", id, state.getHealth());
        }
    }
}
