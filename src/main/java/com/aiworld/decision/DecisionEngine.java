package com.aiworld.decision;

import com.aiworld.action.*;
import com.aiworld.core.World;
import com.aiworld.llm.Strategy;
import com.aiworld.llm.StrategyManager;
import com.aiworld.model.Location;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * Pair of (moveTarget, motivationScore, reason) produced by
     * {@link #chooseMoveTargetAndMotivation}.
     *
     * The reason is forwarded to {@link MoveAction} so the Strategy multiplier
     * layer can distinguish survival-critical movement (FOOD, MEDICINE) from
     * social or exploratory movement (ALLY, EXPLORE).
     */
    private record MoveCandidate(Location target, double motivation, MoveAction.Reason reason) {}

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

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
            log.debug("[{}] has no viable action — idling", npc.getId());
            return;
        }

        // Select the action with the highest strategy-weighted utility
        Action chosen = executable.stream()
            .max(Comparator.comparingDouble(a -> scoreAction(a, npc, world)))
            .orElseThrow();

        chosen.execute(npc, world);
        log.info("[{}] Rule engine → {}", npc.getId(), chosen.getName());
        npc.recordAction(world.getCurrentTick(), chosen.getName());
    }

    /**
     * Scores an action using its base utility multiplied by the current
     * strategy's action multiplier (if a strategy is active).
     *
     * Example: base utility 0.4 for Gather + GATHER_FOOD strategy (×2.5) = 1.0.
     * This causes the rule engine to prefer Gather without bypassing any
     * canExecute() safety checks.
     *
     * For {@link MoveAction}, the lookup key is {@code "Move:<REASON>"} rather
     * than the display name {@code "Move -> (x,y)"}. This lets strategies apply
     * different multipliers to survival-critical movement (FOOD, MEDICINE) vs
     * social or exploratory movement (ALLY, EXPLORE) — fixing the bug where
     * GATHER_FOOD and SURVIVE suppressed the very movements needed to survive.
     */
    private double scoreAction(Action action, AbstractNPC npc, World world) {
        double utility = action.estimatedUtility(npc, world);
        StrategyManager sm = npc.getStrategyManager();
        if (sm != null) {
            Strategy strategy = sm.getCurrentStrategy();
            String lookupKey = (action instanceof MoveAction move)
                ? "Move:" + move.getReason().name()
                : action.getName();
            utility *= strategy.getActionMultiplier(lookupKey);
        }
        return utility;
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

        MoveCandidate move = chooseMoveTargetAndMotivation(npc, world);
        if (move != null) {
            candidates.add(new MoveAction(move.target(), move.motivation(), move.reason()));
        }

        // Add one AttackAction candidate per nearby NPC — utility system picks the best target
        for (AbstractNPC t : findAttackTargets(npc, world)) {
            candidates.add(new AttackAction(t));
        }

        // Add one StealAction candidate per nearby NPC with food — utility system picks the best target
        for (AbstractNPC t : findStealTargets(npc, world)) {
            candidates.add(new StealAction(t));
        }

        return candidates;
    }

    /**
     * Chooses where the NPC should move AND computes the motivation score and
     * movement reason for that move in a single pass. This prevents the previous
     * two-method design where the same threshold conditions had to be kept in sync
     * across two separate methods.
     *
     * Priority:
     *  1. Nearest medicine — only when health is critical (< 40%)    → Reason.MEDICINE
     *  2. Nearest non-depleted food resource — only when hungry (< 50%) → Reason.FOOD
     *  3. Nearest trusted ally (social-driven)                        → Reason.ALLY
     *  4. Random adjacent tile (exploration-driven)                   → Reason.EXPLORE
     *
     * The reason is forwarded to {@link MoveAction} so strategy multipliers can
     * distinguish survival-critical movement from social or exploratory movement.
     *
     * Returns null if no valid move target exists.
     */
    private MoveCandidate chooseMoveTargetAndMotivation(AbstractNPC npc, World world) {
        // 1. Seek medicine when health is critical
        if (npc.getState().getHealthRatio() < 0.4) {
            Location nearestMedicine = world.getNearestResourceLocation(
                npc.getState().getLocation(), Resource.Type.MEDICINE);
            if (nearestMedicine != null)
                return new MoveCandidate(nearestMedicine,
                    1.0 - npc.getState().getHealthRatio(), MoveAction.Reason.MEDICINE);
        }

        // 2. Seek food only when actually hungry
        if (npc.getState().getFoodRatio() < 0.5) {
            Location nearestFood = world.getNearestResourceLocation(
                npc.getState().getLocation(), Resource.Type.FOOD);
            if (nearestFood != null)
                return new MoveCandidate(nearestFood,
                    1.0 - npc.getState().getFoodRatio(), MoveAction.Reason.FOOD);
        }

        // 3. Look for a trusted ally
        Location allyLocation = world.getNearestTrustedAllyLocation(npc);
        if (allyLocation != null) {
            double social = npc.getGoalSystem().getUrgency("Social", npc.getState());
            return new MoveCandidate(allyLocation, social, MoveAction.Reason.ALLY);
        }

        // 4. Random exploration move — use whichever drive is stronger so that
        //    a lonely Diplomat (social weight=0.9, no trusted ally reachable)
        //    still wanders with urgency ≈0.9 rather than the lower explore value.
        double social  = npc.getGoalSystem().getUrgency("Social",  npc.getState());
        double explore = npc.getGoalSystem().getUrgency("Explore", npc.getState());
        Location random = randomAdjacentLocation(npc.getState().getLocation(), world);
        return new MoveCandidate(random, Math.max(social, explore), MoveAction.Reason.EXPLORE);
    }

    /**
     * Returns all NPCs within attack radius as candidates.
     *
     * All scoring — including retaliation bonuses — lives in
     * {@link AttackAction#estimatedUtility}, so candidate generation
     * is purely spatial. This prevents the target-selection/utility-mismatch
     * bug where the pre-filter picks target A but utility would prefer target B.
     */
    private List<AbstractNPC> findAttackTargets(AbstractNPC npc, World world) {
        return world.getNPCsNear(npc, AttackAction.ATTACK_RADIUS);
    }

    /**
     * Returns all NPCs within steal radius who have food as candidates.
     *
     * All scoring lives in {@link StealAction#estimatedUtility}.
     */
    private List<AbstractNPC> findStealTargets(AbstractNPC npc, World world) {
        List<AbstractNPC> nearby = world.getNPCsNear(npc, StealAction.STEAL_RADIUS);
        List<AbstractNPC> result = new ArrayList<>();
        for (AbstractNPC other : nearby) {
            if (other.getState().getFood() > 0) result.add(other);
        }
        return result;
    }

    /**
     * Returns a random adjacent tile, choosing only from directions that stay
     * within world bounds — avoids bias toward corners caused by clamping.
     */
    private Location randomAdjacentLocation(Location current, World world) {
        int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<Location> valid = new ArrayList<>();
        for (int[] d : deltas) {
            int nx = current.getX() + d[0];
            int ny = current.getY() + d[1];
            if (nx >= 0 && nx < world.getWidth() && ny >= 0 && ny < world.getHeight()) {
                valid.add(new Location(nx, ny));
            }
        }
        return valid.isEmpty() ? current : valid.get(ThreadLocalRandom.current().nextInt(valid.size()));
    }
}
