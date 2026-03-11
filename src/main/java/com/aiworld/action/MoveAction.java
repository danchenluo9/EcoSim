package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.goal.ExploreGoal;
import com.aiworld.model.Location;
import com.aiworld.npc.AbstractNPC;

/**
 * MoveAction — steps the NPC one tile toward a target location.
 *
 * Moving costs energy. If the NPC's ExploreGoal is active, movement
 * resets its boredom counter, creating a feedback loop that drives
 * natural roaming behavior and eventual territory formation.
 */
public class MoveAction implements Action {

    private final Location target;

    public MoveAction(Location target) {
        this.target = target;
    }

    @Override
    public String getName() { return "Move -> " + target; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        // Can't move if already there or completely exhausted
        return !npc.getState().getLocation().equals(target)
            && npc.getState().getEnergy() > 5;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        Location current  = npc.getState().getLocation();
        Location nextStep = current.stepToward(target);

        npc.getState().setLocation(nextStep);
        npc.getState().depleteEnergy(2);

        // Notify ExploreGoal so boredom resets
        npc.getGoalSystem().getGoalByType(ExploreGoal.class)
           .ifPresent(ExploreGoal::onMoved);

        System.out.printf("[%s] moved %s -> %s%n",
            npc.getId(), current, nextStep);
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        return npc.getGoalSystem().getUrgency("Explore");
    }
}
