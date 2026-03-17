package com.aiworld.decision;

import com.aiworld.action.*;
import com.aiworld.core.World;
import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * DecisionEngine — the NPC's "brain."
 *
 * Algorithm (Utility-Based Selection):
 *  1. Build a candidate action list for the current context
 *  2. Filter to executable actions (canExecute)
 *  3. Score each action with estimatedUtility
 *  4. Execute the highest-scoring action
 *
 * Extension points:
 *  - Replace step 3 with a learned utility function (RL agent)
 *  - Add look-ahead planning (simulate future states, pick best branch)
 *  - Add group coordination (check what allies are doing before deciding)
 */
public class DecisionEngine {

    private static final Random RANDOM = new Random();

    /**
     * Selects and executes the best action for this NPC this tick.
     * Falls back to idle if no action is viable.
     *
     * @param npc   the NPC making the decision
     * @param world current world snapshot
     */
    public void decide(AbstractNPC npc, World world) {
        List<Action> candidates = buildCandidateActions(npc, world);

        List<Action> executable = new ArrayList<>();
        for (Action action : candidates) {
            if (action.canExecute(npc, world)) {
                executable.add(action);
            }
        }

        if (executable.isEmpty()) {
            System.out.printf("[%s] has no viable action — idling%n", npc.getId());
            return;
        }

        // Select the action with the highest estimated utility
        Action chosen = executable.stream()
            .max(Comparator.comparingDouble(a -> a.estimatedUtility(npc, world)))
            .orElseThrow();

        chosen.execute(npc, world);
    }

    /**
     * Builds the set of candidate actions the NPC should consider.
     *
     * Candidate generation is context-sensitive:
     * - MoveAction targets are chosen based on memory (known resources,
     *   last-seen allies, or unexplored directions)
     * - Only one MoveAction candidate per tick to avoid combinatorial explosion
     *
     * @return list of candidate actions to evaluate
     */
    private List<Action> buildCandidateActions(AbstractNPC npc, World world) {
        List<Action> candidates = new ArrayList<>();

        candidates.add(new RestAction());
        candidates.add(new GatherAction());
        candidates.add(new InteractAction());

        Location moveTarget = chooseMoveTarget(npc, world);
        if (moveTarget != null) {
            candidates.add(new MoveAction(moveTarget));
        }

        return candidates;
    }

    /**
     * Heuristic for choosing where the NPC should move.
     *
     * Priority:
     *  1. Nearest non-depleted food resource (survival-driven)
     *  2. Nearest trusted ally (social-driven)
     *  3. Random adjacent tile (exploration-driven)
     */
    private Location chooseMoveTarget(AbstractNPC npc, World world) {
        // 1. Look for the nearest food
        Location nearestFood = world.getNearestResourceLocation(
            npc.getState().getLocation(), Resource.Type.FOOD);
        if (nearestFood != null) return nearestFood;

        // 2. Look for a trusted ally
        Location allyLocation = world.getNearestTrustedAllyLocation(npc);
        if (allyLocation != null) return allyLocation;

        // 3. Random exploration move
        return randomAdjacentLocation(npc.getState().getLocation(), world);
    }

    /** Returns a random adjacent tile within world bounds. */
    private Location randomAdjacentLocation(Location current, World world) {
        int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] d = deltas[RANDOM.nextInt(deltas.length)];
        int nx = Math.max(0, Math.min(world.getWidth()  - 1, current.getX() + d[0]));
        int ny = Math.max(0, Math.min(world.getHeight() - 1, current.getY() + d[1]));
        return new Location(nx, ny);
    }
}
