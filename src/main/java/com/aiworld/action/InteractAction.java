package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.goal.SocialGoal;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Optional;

/**
 * InteractAction — NPC cooperates with a nearby trusted NPC.
 *
 * Picks the most trusted neutral-or-friendly NPC (trust ≥ 0.5) and shares
 * food with them. Strangers start at trust=0.5 and qualify, so NPCs naturally
 * befriend new neighbours before deciding whether to trust or distrust them.
 *
 * Hostile acquisition (theft) is handled by StealAction. AttackAction handles
 * violence. This action is purely cooperative — no competitive fallback.
 *
 * A minimum social urgency gate ensures low-social archetypes (e.g., Fighter,
 * whose SocialGoal weight is 0.05) don't share food every tick.
 */
public class InteractAction implements Action {

    private static final Logger log = LoggerFactory.getLogger(InteractAction.class);

    private static final int    INTERACTION_RADIUS   = 2;
    private static final double MIN_SOCIAL_URGENCY   = 0.1;   // blocks Fighter (max ~0.05)
    private static final double MIN_FOOD_RATIO_SHARE = 0.3;   // don't share food while in survival territory

    @Override
    public String getName() { return "Interact"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        if (npc.getGoalSystem().getUrgency("Social", npc.getState()) < MIN_SOCIAL_URGENCY)
            return false;
        // Don't share food while in survival territory — a starving NPC giving food away
        // would contradict the SurvivalGoal and hasten their own death.
        if (npc.getState().getFoodRatio() < MIN_FOOD_RATIO_SHARE)
            return false;
        return bestInteractionTarget(npc, world).isPresent();
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        bestInteractionTarget(npc, world)
            .ifPresent(target -> performCooperativeInteraction(npc, target, world));
    }

    /**
     * Finds the most-trusted nearby NPC above the trust gate.
     * Used by both canExecute and execute to avoid duplicated filtering + max logic.
     * getNPCsNear already excludes dead NPCs — no extra isDead() filter needed.
     */
    private Optional<AbstractNPC> bestInteractionTarget(AbstractNPC npc, World world) {
        return world.getNPCsNear(npc, INTERACTION_RADIUS).stream()
            .filter(other -> trustOf(npc, other) >= 0.5)
            .max(Comparator.comparingDouble(other -> trustOf(npc, other)));
    }

    private static double trustOf(AbstractNPC observer, AbstractNPC target) {
        return observer.getMemory().getImpression(target.getId())
            .map(imp -> imp.getTrust()).orElse(0.5);
    }

    /**
     * Cooperative interaction: share food, boosting both NPCs' trust.
     * Foundation for alliance formation.
     */
    private void performCooperativeInteraction(AbstractNPC actor,
                                               AbstractNPC target,
                                               World world) {
        int sharedFood = Math.min(10, (actor.getState().getFood() + 3) / 4);
        actor.getState().depleteFood(sharedFood);
        target.getState().addFood(sharedFood);

        actor.getMemory().recordPositiveInteraction(target.getId(), 0.1);
        target.getMemory().recordPositiveInteraction(actor.getId(), 0.1);

        actor.getMemory().addEvent(new MemoryEvent(
            world.getCurrentTick(),
            MemoryEvent.EventType.SHARED_FOOD,
            "Shared food with " + target.getId(),
            actor.getState().getLocation(),
            +0.6, target.getId()
        ));
        target.getMemory().addEvent(new MemoryEvent(
            world.getCurrentTick(),
            MemoryEvent.EventType.SHARED_FOOD,
            "Received food from " + actor.getId(),
            target.getState().getLocation(),
            +0.6, actor.getId()
        ));

        // Both sides participated — reset loneliness counter for both
        actor.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);
        target.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);
        log.info("[{}] cooperated with [{}], shared {} food",
            actor.getId(), target.getId(), sharedFood);
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        return npc.getGoalSystem().getUrgency("Social", npc.getState());
    }
}
