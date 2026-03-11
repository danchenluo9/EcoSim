package com.aiworld.npc;

import com.aiworld.core.World;
import com.aiworld.goal.ExploreGoal;
import com.aiworld.goal.SocialGoal;
import com.aiworld.goal.SurvivalGoal;
import com.aiworld.model.Location;
import com.aiworld.model.MemoryEvent;

/**
 * NPC — the default general-purpose agent.
 *
 * Comes pre-wired with all three standard goals at balanced weights.
 * Extend this class (or AbstractNPC directly) to create specialized
 * archetypes:
 *   - ForagerNPC    (survival weight 0.9, social 0.1)
 *   - LeaderNPC     (social weight 0.9, manages group migration)
 *   - ScavengerNPC  (competes rather than cooperates by default)
 */
public class NPC extends AbstractNPC {

    public NPC(String id, Location startLocation) {
        super(
            id,
            new NPCState(80, 70, 100, startLocation),
            buildDefaultGoalSystem(),
            new Memory()
        );
    }

    /** Creates a GoalSystem with balanced survival/explore/social goals. */
    private static GoalSystem buildDefaultGoalSystem() {
        GoalSystem gs = new GoalSystem();
        gs.addGoal(new SurvivalGoal(0.7));  // survival weighted highest
        gs.addGoal(new ExploreGoal(0.4));
        gs.addGoal(new SocialGoal(0.3));
        return gs;
    }

    @Override
    public String getArchetype() { return "NPC"; }

    /**
     * Perception phase: scan surroundings and record notable observations
     * as memory events before deciding on an action.
     */
    @Override
    protected void onPreUpdate(World world) {
        world.getNPCsNear(state.getLocation(), 3).stream()
            .filter(other -> !other.getId().equals(this.id))
            .forEach(other -> memory.addEvent(new MemoryEvent(
                world.getCurrentTick(),
                MemoryEvent.EventType.MET_NPC,
                "Spotted " + other.getId() + " nearby",
                other.getState().getLocation(),
                0.0
            )));
    }

    /** Post-action status log — warn if health is critical. */
    @Override
    protected void onPostUpdate(World world) {
        if (state.getHealth() <= 20) {
            System.out.printf("[%s] WARNING: critically low health (%d)!%n",
                id, state.getHealth());
        }
    }
}
