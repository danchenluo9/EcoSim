package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * GatherAction — collects resources at the NPC's current location.
 *
 * Handles two resource types:
 *  - FOOD:     restores food; multiple NPCs competing for the same node
 *              creates resource contention and drives migration behavior.
 *  - MEDICINE: restores health; only triggered when health is below the
 *              heal threshold — NPCs won't waste medicine when healthy.
 *              No social contention (medicine is personal, not competitive).
 */
public class GatherAction implements Action {

    private static final Logger log = LoggerFactory.getLogger(GatherAction.class);

    private static final int    GATHER_AMOUNT   = 15;
    private static final int    HEAL_AMOUNT     = 20;
    private static final int    ENERGY_COST     = 3;
    private static final double MEDICINE_HEALTH_THRESHOLD = 0.6; // only use medicine when genuinely hurt (below 60%)

    /** Cached from canExecute to avoid a second linear scan in execute. */
    private Resource cachedResource;

    @Override
    public String getName() { return "Gather"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        Optional<Resource> resource = world.getResourceAt(npc.getState().getLocation());
        if (resource.isEmpty()) return false;

        Resource.Type type = resource.get().getType();
        boolean eligible;
        if (type == Resource.Type.FOOD)
            eligible = npc.getState().getFood() < npc.getState().getMaxFood();
        else if (type == Resource.Type.MEDICINE)
            eligible = npc.getState().getHealthRatio() < MEDICINE_HEALTH_THRESHOLD;
        else
            eligible = false;

        if (eligible) cachedResource = resource.get();
        return eligible;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        // Use the resource cached in canExecute — avoids a second linear scan.
        // cachedResource is non-null here because execute is only called after canExecute returns true.
        Resource resource = cachedResource;
        if (resource != null && !resource.isDepleted()) {
            if (resource.getType() == Resource.Type.MEDICINE) {
                int healed = resource.consume(HEAL_AMOUNT);
                npc.getState().restoreHealth(healed);
                npc.getState().depleteEnergy(ENERGY_COST);
                log.info("[{}] used medicine at {} (+{} health, resource left: {})",
                    npc.getId(), npc.getState().getLocation(),
                    healed, resource.getQuantity());
            } else {
                // FOOD path
                int gathered = resource.consume(GATHER_AMOUNT);
                npc.getState().addFood(gathered);
                npc.getState().depleteEnergy(ENERGY_COST);
                log.info("[{}] gathered {} food at {} (resource left: {})",
                    npc.getId(), gathered,
                    npc.getState().getLocation(), resource.getQuantity());

                // Resource contention: if the node ran short, only NPCs sharing the exact
                // same tile are genuine competitors. Radius-1 was wrong — it penalised
                // passing NPCs who weren't gathering at all, creating false hostility.
                if (gathered < GATHER_AMOUNT) {
                    List<AbstractNPC> competitors = world.getNPCsNear(npc, 0);
                    for (AbstractNPC rival : competitors) {
                        npc.getMemory().recordNegativeInteraction(rival.getId(), 0.05);
                        rival.getMemory().recordNegativeInteraction(npc.getId(), 0.05);
                    }
                }
            }
        }
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        // Re-use the cached resource from canExecute when available.
        // DecisionEngine always calls canExecute before estimatedUtility for the same
        // action instance, so cachedResource is non-null for eligible actions.
        Resource resource = cachedResource;
        if (resource == null || resource.getQuantity() == 0) return 0.0;

        if (resource.getType() == Resource.Type.MEDICINE)
            return 1.0 - npc.getState().getHealthRatio();

        return 1.0 - npc.getState().getFoodRatio();
    }
}
