package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;

import java.util.List;
import java.util.Optional;

/**
 * GatherAction — collects resources at the NPC's current location.
 *
 * Gathering is the primary way NPCs restore food. Since resources are
 * finite and regenerate slowly, multiple NPCs competing for the same node
 * creates natural resource contention and drives migration behavior.
 */
public class GatherAction implements Action {

    private static final int GATHER_AMOUNT = 15;
    private static final int ENERGY_COST   = 3;

    @Override
    public String getName() { return "Gather"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        Optional<Resource> resource = world.getResourceAt(npc.getState().getLocation());
        return resource.isPresent()
            && !resource.get().isDepleted()
            && resource.get().getType() == Resource.Type.FOOD;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        world.getResourceAt(npc.getState().getLocation()).ifPresent(resource -> {
            int gathered = resource.consume(GATHER_AMOUNT);
            npc.getState().addFood(gathered);
            npc.getState().depleteEnergy(ENERGY_COST);
            System.out.printf("[%s] gathered %d food at %s (resource left: %d)%n",
                npc.getId(), gathered,
                npc.getState().getLocation(), resource.getQuantity());

            // Resource contention: if the node ran short, nearby NPCs are competitors
            if (gathered < GATHER_AMOUNT) {
                List<AbstractNPC> competitors = world.getNPCsNear(
                    npc.getState().getLocation(), 0);
                competitors.remove(npc);
                for (AbstractNPC rival : competitors) {
                    npc.getMemory().recordNegativeInteraction(rival.getId(), 0.05);
                    rival.getMemory().recordNegativeInteraction(npc.getId(), 0.05);
                }
            }
        });
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        double foodUrgency  = 1.0 - npc.getState().getFoodRatio();
        boolean hasResource = world.getResourceAt(npc.getState().getLocation())
                                   .map(r -> !r.isDepleted()).orElse(false);
        return hasResource ? foodUrgency : 0.0;
    }
}
