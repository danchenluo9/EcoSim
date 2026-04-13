package com.aiworld.llm;

import com.aiworld.core.World;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import com.aiworld.npc.NPCState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Builds the context prompt sent to the LLM for strategic planning.
 *
 * The prompt gives the model a complete snapshot of the NPC's situation:
 * vitals, goal urgencies, recent memory, nearby NPCs, and the current
 * strategy — enough for the model to reason about what the NPC should
 * prioritise over the next 30–60 ticks.
 *
 * The model is explicitly told NOT to choose individual actions, only
 * a high-level strategy from the predefined list.
 *
 * Example prompt template (filled in at runtime):
 * <pre>
 *   You are advising an NPC in a survival simulation.
 *   Choose a HIGH-LEVEL STRATEGY — NOT individual actions.
 *   ...
 * </pre>
 */
public class LLMPromptBuilder {

    /** Maximum recent events to include (keeps the prompt short). */
    private static final int MAX_MEMORY_EVENTS = 6;

    public static String build(AbstractNPC npc, World world) {
        NPCState state = npc.getState();
        StringBuilder sb = new StringBuilder(1024);

        // ── Header ────────────────────────────────────────────────────
        sb.append("You ARE ").append(npc.getId())
          .append(", an agent in a competitive survival world.\n");
        sb.append("Think only of your own survival, resources, and standing.\n");
        sb.append("Choose a HIGH-LEVEL STRATEGY that reflects what YOU would do — not what is safest in general.\n");
        sb.append("Your rule-based instincts handle moment-to-moment actions (Move/Gather/Rest/Interact/Attack).\n");
        sb.append("This strategy biases those instincts for the next 30-60 ticks.\n\n");

        // ── NPC status ────────────────────────────────────────────────
        sb.append("=== NPC Status ===\n");
        sb.append("ID:       ").append(npc.getId()).append("\n");
        sb.append("Archetype: ").append(npc.getArchetype()).append("\n");
        sb.append(String.format("Health:   %d/100%n", state.getHealth()));
        sb.append(String.format("Energy:   %d/100%n", state.getEnergy()));
        sb.append(String.format("Food:     %d/100%n", state.getFood()));
        sb.append(String.format("Location: (%d, %d), Age: %d ticks%n",
            state.getLocation().getX(), state.getLocation().getY(), state.getAge()));
        sb.append("\n");

        // ── Goal urgencies ────────────────────────────────────────────
        // Driven from the live GoalSystem — automatically includes AggressionGoal
        // for Fighter, and any future goals without requiring changes here.
        sb.append("=== Goal Urgencies (0.0 = calm, 1.0 = critical) ===\n");
        npc.getGoalSystem().getGoals().forEach(g ->
            sb.append(String.format("  %-14s %.2f%n",
                g.getName() + ":", g.computeUrgency(state))));
        sb.append("\n");

        // ── Recent memory ─────────────────────────────────────────────
        sb.append("=== Recent Memory (last ").append(MAX_MEMORY_EVENTS).append(" events) ===\n");
        Deque<MemoryEvent> allEvents = npc.getMemory().getAllEvents();
        if (allEvents.isEmpty()) {
            sb.append("No notable events recorded yet.\n");
        } else {
            // Collect last N events without copying the whole deque
            ArrayDeque<MemoryEvent> recent = new ArrayDeque<>(MAX_MEMORY_EVENTS);
            java.util.Iterator<MemoryEvent> descIt = allEvents.descendingIterator();
            int collected = 0;
            while (descIt.hasNext() && collected++ < MAX_MEMORY_EVENTS) {
                recent.addFirst(descIt.next());
            }
            for (MemoryEvent e : recent) {
                sb.append(String.format("  [tick %d] %s — %s%n",
                    e.getTick(), e.getType(), e.getDescription()));
            }
        }
        sb.append("\n");

        // ── Nearby NPCs ───────────────────────────────────────────────
        sb.append("=== Nearby NPCs (radius 3) ===\n");
        List<AbstractNPC> nearby = world.getNPCsNear(npc, 3);
        if (nearby.isEmpty()) {
            sb.append("None.\n");
        } else {
            for (AbstractNPC other : nearby) {
                double trust     = npc.getMemory().getImpression(other.getId())
                                      .map(imp -> imp.getTrust()).orElse(0.5);
                double hostility = npc.getMemory().getImpression(other.getId())
                                      .map(imp -> imp.getHostility()).orElse(0.0);
                int dist = other.getState().getLocation().distanceTo(state.getLocation());

                // Count past attacks and robberies to give Claude relationship context.
                // Both are sourced from memory events, so counts reflect only what the
                // NPC can still remember (last 50 events — old incidents may be forgotten).
                String otherId = other.getId();
                long timesAttackedByThem = npc.getMemory()
                    .getEventsOfType(MemoryEvent.EventType.WAS_ATTACKED).stream()
                    .filter(e -> otherId.equals(e.getTargetId())).count();
                long timesAttackedThem = npc.getMemory()
                    .getEventsOfType(MemoryEvent.EventType.ATTACKED_NPC).stream()
                    .filter(e -> otherId.equals(e.getTargetId())).count();
                long timesRobbedByThem = npc.getMemory()
                    .getEventsOfType(MemoryEvent.EventType.WAS_ROBBED).stream()
                    .filter(e -> otherId.equals(e.getTargetId())).count();
                long timesRobbedThem = npc.getMemory()
                    .getEventsOfType(MemoryEvent.EventType.STOLE_FOOD).stream()
                    .filter(e -> otherId.equals(e.getTargetId())).count();

                sb.append(String.format(
                    "  %s — trust: %.2f, hostility: %.2f, distance: %d",
                    other.getId(), trust, hostility, dist));
                if (timesAttackedByThem > 0)
                    sb.append(String.format(", attacked you %d time(s)", timesAttackedByThem));
                if (timesAttackedThem > 0)
                    sb.append(String.format(", you attacked them %d time(s)", timesAttackedThem));
                if (timesRobbedByThem > 0)
                    sb.append(String.format(", stole food from you %d time(s)", timesRobbedByThem));
                if (timesRobbedThem > 0)
                    sb.append(String.format(", you stole food from them %d time(s)", timesRobbedThem));
                sb.append("\n");
            }
        }
        sb.append("\n");

        // ── Current strategy ──────────────────────────────────────────
        StrategyManager sm = npc.getStrategyManager();
        if (sm != null && sm.getCurrentStrategy() != null) {
            Strategy current = sm.getCurrentStrategy();
            long age = world.getCurrentTick() - current.getSetAtTick();
            sb.append("=== Current Strategy (adopted ").append(age).append(" ticks ago) ===\n");
            sb.append("  Type:   ").append(current.getType()).append("\n");
            sb.append("  Intent: ").append(current.getIntent()).append("\n");
            sb.append("  Reason: ").append(current.getReason()).append("\n\n");
        }

        // ── Available strategies ──────────────────────────────────────
        sb.append("=== Your Strategic Options ===\n");
        sb.append("  GATHER_FOOD     - focus on securing food before others take it\n");
        sb.append("  SEEK_ALLIES     - find and strengthen bonds with trusted NPCs\n");
        sb.append("  EXPLORE         - roam to discover unclaimed resources and territory\n");
        sb.append("  AVOID_CONFLICT  - stay away from danger when you are too weak to fight\n");
        sb.append("  CONSERVE_ENERGY - rest and recover before your next move\n");
        sb.append("  SURVIVE         - emergency: food and rest above everything else\n");
        sb.append("  RETALIATE       - hunt down whoever attacked you and make them pay\n");
        sb.append("\n");

        // ── Output format ─────────────────────────────────────────────
        sb.append("What do YOU do next? Choose the strategy that matches your situation and character.\n");
        sb.append("Respond ONLY with this JSON (no extra text, no markdown):\n");
        sb.append("{\n");
        sb.append("  \"strategy\": \"<one of the strategy names above>\",\n");
        sb.append("  \"intent\":   \"<what the NPC should achieve in one sentence>\",\n");
        sb.append("  \"reason\":   \"<brief explanation of why this strategy fits the current situation>\"\n");
        sb.append("}");

        return sb.toString();
    }
}
