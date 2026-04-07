package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.llm.Strategy;
import com.aiworld.llm.StrategyManager;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Optional;

/**
 * AttackAction — NPC physically attacks an adjacent NPC.
 *
 * Triggered by high hostility or desperate survival need.
 * A successful attack deals health damage to the target, costs the attacker
 * energy, and escalates hostility on both sides. The target records a
 * WAS_ATTACKED memory event, which immediately triggers an LLM strategy
 * re-evaluation (fight-or-flight response).
 *
 * Cooldown prevents continuous attack spam: an NPC won't attack again
 * within ATTACK_COOLDOWN_TICKS of their last strike.
 */
public class AttackAction implements Action {

    private static final Logger log = LoggerFactory.getLogger(AttackAction.class);

    public  static final int    ATTACK_RADIUS           = 2;
    private static final int    HEALTH_DAMAGE           = 15;
    private static final int    ENERGY_COST             = 15;
    private static final int    ATTACK_COOLDOWN_TICKS   = 8;  // per-target cooldown
    private static final int    GLOBAL_ATTACK_COOLDOWN  = 3;  // min ticks between any attacks
    private static final double DESPERATION_THRESHOLD   = 0.35; // food ratio below which desperation kicks in
    private static final int    LOOT_HEALTH_THRESHOLD   = 20;   // loot food when target is at or below this health

    private final AbstractNPC target;

    public AttackAction(AbstractNPC target) {
        this.target = target;
    }

    @Override
    public String getName() { return "Attack:" + target.getId(); }

    @Override
    public boolean canExecute(AbstractNPC npc, World world) {
        if (target.getState().isDead()) return false;
        if (npc.getState().getEnergy() < ENERGY_COST + 5) return false;

        // Global cooldown: can't attack anyone within GLOBAL_ATTACK_COOLDOWN ticks of last strike
        boolean globalCooldown = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.ATTACKED_NPC)
            .stream()
            .anyMatch(e -> world.getCurrentTick() - e.getTick() < GLOBAL_ATTACK_COOLDOWN);
        if (globalCooldown) return false;

        // Per-target cooldown — use targetId field directly, not description parsing
        boolean onCooldown = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.ATTACKED_NPC)
            .stream()
            .anyMatch(e -> target.getId().equals(e.getTargetId())
                        && world.getCurrentTick() - e.getTick() < ATTACK_COOLDOWN_TICKS);
        if (onCooldown) return false;

        // Target must be within attack radius
        int dist = npc.getState().getLocation().distanceTo(target.getState().getLocation());
        return dist <= ATTACK_RADIUS;
    }

    @Override
    public void execute(AbstractNPC npc, World world) {
        long tick = world.getCurrentTick();

        // Deal damage
        target.getState().damageHealth(HEALTH_DAMAGE);
        npc.getState().depleteEnergy(ENERGY_COST);

        log.info("[{}] ATTACKED [{}] for {} damage! (target health: {})",
            npc.getId(), target.getId(), HEALTH_DAMAGE, target.getState().getHealth());

        // Attacker memory
        npc.getMemory().addEvent(new MemoryEvent(
            tick, MemoryEvent.EventType.ATTACKED_NPC,
            "Attacked " + target.getId(),
            npc.getState().getLocation(),
            -0.4, target.getId()
        ));
        npc.getMemory().recordNegativeInteraction(target.getId(), 0.25);

        // Target memory — WAS_ATTACKED triggers LLM strategy re-evaluation
        target.getMemory().addEvent(new MemoryEvent(
            tick, MemoryEvent.EventType.WAS_ATTACKED,
            "Was attacked by " + npc.getId() + ", lost " + HEALTH_DAMAGE + " health",
            target.getState().getLocation(),
            -0.9, npc.getId()
        ));
        target.getMemory().recordNegativeInteraction(npc.getId(), 0.4);

        // Loot food when target is downed or critically wounded — closes the gap where
        // food-desperate NPCs attack and gain nothing from the fight.
        if (target.getState().getHealth() <= LOOT_HEALTH_THRESHOLD) {
            int looted = Math.min(20, target.getState().getFood());
            if (looted > 0) {
                target.getState().depleteFood(looted);
                npc.getState().addFood(looted);
                log.info("[{}] looted {} food from critically wounded [{}]",
                    npc.getId(), looted, target.getId());
            }
        }

        // Log death if it occurs
        if (target.getState().isDead()) {
            log.info("[{}] has been killed by [{}]!", target.getId(), npc.getId());
            npc.getMemory().addEvent(new MemoryEvent(
                tick, MemoryEvent.EventType.OBSERVED_DEATH,
                "Killed " + target.getId(),
                npc.getState().getLocation(),
                -0.5, target.getId()
            ));
        }
    }

    /**
     * Four independent motivation components — any one can push an NPC to attack:
     *
     *  1. Personality  (AggressionGoal) — Fighters attack opportunistically; scaled by
     *                                     distrust so they won't strike their own allies.
     *  2. Hostility    (earned)         — any NPC retaliates against someone they hate.
     *  3. Desperation  (survival)       — last-resort food acquisition when starving.
     *  4. Retaliation  (RETALIATE)      — strong bonus for the specific NPC who attacked
     *                                     or robbed this NPC most recently, ensuring
     *                                     retaliation targets the actual aggressor.
     *
     * This replaces the old motivation gates in canExecute so that the strategy
     * multiplier layer can bias each behaviour uniformly.
     */
    @Override
    public double estimatedUtility(AbstractNPC npc, World world) {
        double trust = npc.getMemory()
            .getImpression(target.getId())
            .map(imp -> imp.getTrust())
            .orElse(0.5);

        // Component 1: personality — Fighter always scores here; others score 0
        double aggressionUrgency = npc.getGoalSystem().getUrgency("Aggression", npc.getState());
        double personality = aggressionUrgency * (1.0 - trust);

        // Component 2: earned hostility — any NPC who has been wronged
        double hostility = npc.getMemory()
            .getImpression(target.getId())
            .map(imp -> imp.getHostility())
            .orElse(0.0);

        // Component 3: starvation desperation — last resort for any archetype
        double desperation = Math.max(0, DESPERATION_THRESHOLD - npc.getState().getFoodRatio()) * 2.0;

        // Component 4: retaliation — under RETALIATE strategy, strongly prefer the
        // specific NPC who attacked or robbed us most recently (covers both theft and assault)
        double retaliation = 0.0;
        StrategyManager sm = npc.getStrategyManager();
        if (sm != null && sm.getCurrentStrategy().getType() == Strategy.Type.RETALIATE) {
            Optional<String> conflictSourceId = getMostRecentConflictSource(npc);
            if (conflictSourceId.map(id -> id.equals(target.getId())).orElse(false)) {
                retaliation = 1.0;
            }
        }

        return Math.min(1.0, personality + hostility + desperation + retaliation);
    }

    /**
     * Returns the ID of whoever attacked or robbed this NPC most recently.
     * Used to direct retaliation at the actual aggressor rather than an arbitrary
     * nearby enemy.
     */
    private Optional<String> getMostRecentConflictSource(AbstractNPC npc) {
        MemoryEvent latestAttack = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.WAS_ATTACKED).stream()
            .max(Comparator.comparingLong(MemoryEvent::getTick))
            .orElse(null);
        MemoryEvent latestRobbery = npc.getMemory()
            .getEventsOfType(MemoryEvent.EventType.WAS_ROBBED).stream()
            .max(Comparator.comparingLong(MemoryEvent::getTick))
            .orElse(null);

        MemoryEvent latest;
        if      (latestAttack  == null) latest = latestRobbery;
        else if (latestRobbery == null) latest = latestAttack;
        else latest = latestAttack.getTick() >= latestRobbery.getTick() ? latestAttack : latestRobbery;

        return Optional.ofNullable(latest).map(MemoryEvent::getTargetId);
    }
}
