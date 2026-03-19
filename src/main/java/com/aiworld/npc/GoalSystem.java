package com.aiworld.npc;

import com.aiworld.goal.Goal;

import java.util.ArrayList;
import java.util.Comparator;
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
     * Updates all goal weights based on current state and tick.
     * Called once per tick before action selection.
     */
    public void updateAll(NPCState state, long currentTick) {
        for (Goal goal : goals) {
            goal.updateWeight(state, currentTick);
        }
    }

    /**
     * Returns the goal with the highest current urgency.
     * This is the goal the NPC will try to satisfy this tick.
     */
    public Optional<Goal> getMostUrgentGoal(NPCState state) {
        return goals.stream()
            .max(Comparator.comparingDouble(g -> g.computeUrgency(state)));
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

    public List<Goal> getGoals() { return goals; }
}
