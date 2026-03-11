package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.goal.SocialGoal;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;

import java.util.List;

/**
 * InteractAction — NPC attempts a social interaction with a nearby NPC.
 *
 * Interactions can be cooperative (share food, form alliance) or competitive
 * (steal resources). The outcome depends on trust levels in Memory.
 * This is the primary mechanism for emergent social structures.
 *
 * Extension point: subclass to create AllianceAction, TradeAction, AttackAction.
 */
public class InteractAction implements Action {

    private static final int INTERACTION_RADIUS = 2;
    private AbstractNPC targetNpc;  // resolved in canExecute

    @Override
    public String getName() { return "Interact"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        List<AbstractNPC> nearby = world.getNPCsNear(
            npc.getState().getLocation(), INTERACTION_RADIUS);
        nearby.remove(npc);
        if (nearby.isEmpty()) return false;
        targetNpc = nearby.get(0);
        return true;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        if (targetNpc == null) return;

        double targetTrust = npc.getMemory()
            .getImpression(targetNpc.getId())
            .map(imp -> imp.getTrust())
            .orElse(0.5);

        if (targetTrust >= 0.5) {
            performCooperativeInteraction(npc, targetNpc, world);
        } else {
            performCompetitiveInteraction(npc, targetNpc, world);
        }

        // Notify SocialGoal that interaction occurred
        npc.getGoalSystem().getGoalByType(SocialGoal.class)
           .ifPresent(SocialGoal::onInteracted);
    }

    /**
     * Cooperative interaction: share food, boosting both NPCs' trust.
     * Foundation for alliance formation.
     */
    private void performCooperativeInteraction(AbstractNPC actor,
                                               AbstractNPC target,
                                               World world) {
        int sharedFood = Math.min(10, actor.getState().getFood() / 4);
        actor.getState().depleteFood(sharedFood);
        target.getState().addFood(sharedFood);

        actor.getMemory().recordPositiveInteraction(target.getId(), 0.1);
        target.getMemory().recordPositiveInteraction(actor.getId(), 0.1);

        actor.getMemory().addEvent(new MemoryEvent(
            world.getCurrentTick(),
            MemoryEvent.EventType.SHARED_FOOD,
            "Shared food with " + target.getId(),
            actor.getState().getLocation(),
            +0.6
        ));

        System.out.printf("[%s] cooperated with [%s], shared %d food%n",
            actor.getId(), target.getId(), sharedFood);
    }

    /**
     * Competitive interaction: attempt to steal resources.
     * Damages trust and may trigger hostile response in future ticks.
     */
    private void performCompetitiveInteraction(AbstractNPC actor,
                                               AbstractNPC target,
                                               World world) {
        int stolen = Math.min(5, target.getState().getFood());
        target.getState().depleteFood(stolen);
        actor.getState().addFood(stolen);

        actor.getMemory().recordNegativeInteraction(target.getId(), 0.2);
        target.getMemory().recordNegativeInteraction(actor.getId(), 0.3);

        actor.getMemory().addEvent(new MemoryEvent(
            world.getCurrentTick(),
            MemoryEvent.EventType.WAS_ATTACKED,
            "Stole from " + target.getId(),
            actor.getState().getLocation(),
            -0.4
        ));

        System.out.printf("[%s] competed with [%s], stole %d food%n",
            actor.getId(), target.getId(), stolen);
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        return npc.getGoalSystem().getUrgency("Social");
    }
}
