package com.aiworld.npc;

import com.aiworld.goal.Goal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * GoalSystem manages the NPC's set of active goals and selects the
 * most urgent one each tick.
 *
 * This is a utility-based goal architecture: each goal computes a
 * real-valued urgency, and the system selects the maximum. This allows
 * smooth goal transitions without rigid FSM state switches, which is
 * critical for emergent behavior (e.g., smoothly shifting from
 * survival to socializing as conditions improve).
 */
public class GoalSystem {

    private final List<Goal> goals = new ArrayList<>();

    public void addGoal(Goal goal) {
        goals.add(goal);
    }

    /**
     * Calls {@link Goal#onTick} for every goal, allowing stateful goals
     * (SocialGoal, ExploreGoal) to advance their internal counters.
     * Called once per tick before action selection.
     *
     * Renamed from {@code updateAll} — the previous name implied goal weights
     * change dynamically, but personality weights are final in every
     * implementation. This is purely a per-tick state hook.
     */
    public void tickAll(NPCState state, long currentTick) {
        for (Goal goal : goals) {
            goal.onTick(state, currentTick);
        }
    }

    /**
     * Returns the urgency score of a goal by name.
     * Used by actions to estimate their own utility.
     */
    public double getUrgency(String goalName, NPCState state) {
        return goals.stream()
            .filter(g -> g.getName().equals(goalName))
            .mapToDouble(g -> g.computeUrgency(state))
            .findFirst()
            .orElse(0.0);
    }

    /**
     * Finds a specific goal implementation by class.
     * Used by actions to directly notify goals of relevant events.
     */
    @SuppressWarnings("unchecked")
    public <T extends Goal> Optional<T> getGoalByType(Class<T> clazz) {
        return goals.stream()
            .filter(clazz::isInstance)
            .map(g -> (T) g)
            .findFirst();
    }

    public List<Goal> getGoals() { return Collections.unmodifiableList(goals); }
}
