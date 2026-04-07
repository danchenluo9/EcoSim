package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.model.MemoryEvent;
import com.aiworld.model.NPCImpression;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StealAction — NPC snatches food from a nearby NPC without physical combat.
 *
 * Steal is a survival-driven hostile act. Unlike AttackAction — which is driven
 * by personality (AggressionGoal), earned hostility, or extreme desperation —
 * steal utility is primarily determined by the actor's food need. Any archetype
 * will steal when hungry enough; Fighters just score higher on AttackAction and
 * prefer outright combat when the opportunity exists.
 *
 * Steal does not deal health damage. It escalates hostility less than attack,
 * but does trigger an emergency LLM strategy re-evaluation for the victim via
 * the WAS_ROBBED memory event.
 *
 * canExecute checks only physical feasibility (range, energy, target has food,
 * cooldown). All motivation reasoning lives in estimatedUtility so the strategy
 * multiplier layer can bias it uniformly.
 */
public class StealAction implements Action {

    private static final Logger log = LoggerFactory.getLogger(StealAction.class);

    /** Package-visible so DecisionEngine can use the same radius for target selection. */
    public static final int STEAL_RADIUS = 2;

    private static final int FOOD_TAKEN             = 12;
    private static final int ENERGY_COST            = 5;
    private static final int STEAL_COOLDOWN_TICKS   = 10; // per-target cooldown
    private static final int GLOBAL_STEAL_COOLDOWN  = 3;  // min ticks between any steal

    private final AbstractNPC target;

    public StealAction(AbstractNPC target) {
        this.target = target;
    }

    @Override
    public String getName() { return "Steal:" + target.getId(); }

    // ── Feasibility only — no motivation checks ───────────────────────

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        if (target.getState().isDead())       return false;
        if (target.getState().getFood() <= 0) return false;
        if (npc.getState().getEnergy() < ENERGY_COST) return false;

        int dist = npc.getState().getLocation().distanceTo(target.getState().getLocation());
        if (dist > STEAL_RADIUS) return false;

        // Global cooldown: can't steal from anyone within GLOBAL_STEAL_COOLDOWN ticks of last theft
        boolean globalCooldown = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.STOLE_FOOD)
            .stream()
            .anyMatch(e -> world.getCurrentTick() - e.getTick() < GLOBAL_STEAL_COOLDOWN);
        if (globalCooldown) return false;

        // Per-target cooldown: don't steal from the same NPC back-to-back
        return npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.STOLE_FOOD)
            .stream()
            .noneMatch(e -> target.getId().equals(e.getTargetId())
                         && world.getCurrentTick() - e.getTick() < STEAL_COOLDOWN_TICKS);
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        long tick  = world.getCurrentTick();
        int  taken = Math.min(FOOD_TAKEN, target.getState().getFood());

        target.getState().depleteFood(taken);
        npc.getState().addFood(taken);
        npc.getState().depleteEnergy(ENERGY_COST);

        log.info("[{}] stole {} food from [{}] (target food left: {})",
            npc.getId(), taken, target.getId(), target.getState().getFood());

        // Thief memory
        npc.getMemory().addEvent(new MemoryEvent(
            tick, MemoryEvent.EventType.STOLE_FOOD,
            "Stole " + taken + " food from " + target.getId(),
            npc.getState().getLocation(),
            -0.3, target.getId()
        ));
        npc.getMemory().recordNegativeInteraction(target.getId(), 0.15);

        // Victim memory — WAS_ROBBED triggers LLM strategy re-evaluation
        target.getMemory().addEvent(new MemoryEvent(
            tick, MemoryEvent.EventType.WAS_ROBBED,
            "Was robbed of " + taken + " food by " + npc.getId(),
            target.getState().getLocation(),
            -0.6, npc.getId()
        ));
        target.getMemory().recordNegativeInteraction(npc.getId(), 0.2);
    }

    // ── Motivation: food need is primary, opportunistic hostility is secondary ──

    /** Below this survival need (food > 20%), trust still protects allies from theft. */
    private static final double TRUST_GATE_NEED = 0.8;

    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        double survivalNeed = 1.0 - npc.getState().getFoodRatio();

        double trust = npc.getMemory().getImpression(target.getId())
            .map(NPCImpression::getTrust).orElse(0.5);
        double hostility = npc.getMemory().getImpression(target.getId())
            .map(NPCImpression::getHostility).orElse(0.0);

        // Full trust protection below the gate (food > 20%); gate opens when truly desperate.
        // A linear fade was considered but the binary gate is intentional: the NPC
        // respects allies until starvation forces their hand, then all bets are off.
        double trustPenalty = survivalNeed < TRUST_GATE_NEED ? trust * 0.5 : 0.0;

        return Math.min(1.0, survivalNeed * 0.7 + hostility * 0.3 - trustPenalty);
    }
}
