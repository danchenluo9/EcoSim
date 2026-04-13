package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RestAction — NPC stays in place and recovers energy.
 *
 * Resting consumes food to restore energy (metabolic exchange).
 * If food is also low, resting is risky — the NPC must decide whether
 * to rest and starve or move and exhaust itself. This tradeoff is
 * the core survival tension in the simulation.
 */
public class RestAction implements Action {

    private static final Logger log = LoggerFactory.getLogger(RestAction.class);

    private static final int ENERGY_RESTORE     = 20;
    private static final int FOOD_COST          = 5;
    private static final int REST_THRESHOLD     = 40; // only rest when genuinely tired
                                                       // (was 70 — caused NPCs to rest before
                                                       //  doing anything else at spawn)

    @Override
    public String getName() { return "Rest"; }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        return npc.getState().getEnergy() < REST_THRESHOLD
            && npc.getState().getFood() > 0;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        int foodSpent = Math.min(FOOD_COST, npc.getState().getFood());
        npc.getState().depleteFood(foodSpent);
        npc.getState().restoreEnergy(ENERGY_RESTORE);

        log.debug("[{}] rested — energy: {}, food: {}",
            npc.getId(),
            npc.getState().getEnergy(),
            npc.getState().getFood());
    }

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        double energyUrgency    = 1.0 - npc.getState().getEnergyRatio();
        double foodAvailability = npc.getState().getFoodRatio();
        return energyUrgency * foodAvailability;
    }
}
