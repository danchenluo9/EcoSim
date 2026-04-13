package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.goal.ExploreGoal;
import com.aiworld.model.Location;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MoveAction — steps the NPC one tile toward a target location.
 *
 * Moving costs energy. If the NPC's ExploreGoal is active, movement
 * resets its boredom counter, creating a feedback loop that drives
 * natural roaming behavior and eventual territory formation.
 *
 * The {@code motivation} score is set by DecisionEngine at construction
 * time and reflects WHY the NPC is moving (survival urgency, social
 * urgency, or explore urgency). This ensures a food-seeking move scores
 * as high-urgency even when ExploreGoal is low.
 *
 * The {@code reason} tag allows {@link com.aiworld.llm.Strategy} to apply
 * different multipliers depending on why the NPC is moving. For example,
 * GATHER_FOOD suppresses random movement but should boost movement toward
 * food; SURVIVE should never suppress movement toward medicine.
 */
public class MoveAction implements Action {

    /**
     * Why this NPC is moving — used by the Strategy multiplier layer to
     * distinguish survival-critical movement from social or exploratory movement.
     */
    public enum Reason {
        /** Moving toward a food resource (hunger-driven). */
        FOOD,
        /** Moving toward medicine when health is critically low. */
        MEDICINE,
        /** Moving toward a trusted ally (social motivation or safety in numbers). */
        ALLY,
        /** Random or exploratory movement — no specific resource target. */
        EXPLORE
    }

    private static final Logger log = LoggerFactory.getLogger(MoveAction.class);

    private final Location target;
    private final double   motivation;
    private final Reason   reason;

    public MoveAction(Location target, double motivation, Reason reason) {
        this.target     = target;
        this.motivation = motivation;
        this.reason     = reason;
    }

    public Reason getReason() { return reason; }

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

        log.debug("[{}] moved {} -> {}", npc.getId(), current, nextStep);
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        return motivation;
    }
}
