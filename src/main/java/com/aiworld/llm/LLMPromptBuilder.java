package com.aiworld.llm;

import com.aiworld.core.World;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import com.aiworld.npc.NPCState;

import java.util.ArrayList;
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
        sb.append("You are advising an NPC agent in a survival simulation.\n");
        sb.append("Your job is to choose a HIGH-LEVEL STRATEGY — NOT individual actions.\n");
        sb.append("The NPC's rule-based engine handles moment-to-moment decisions (Move/Gather/Rest/Interact).\n");
        sb.append("A strategy biases those decisions over the next 30-60 ticks.\n\n");

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
        sb.append("=== Goal Urgencies (0.0 = calm, 1.0 = critical) ===\n");
        sb.append(String.format("Survival: %.2f%n", npc.getGoalSystem().getUrgency("Survival", state)));
        sb.append(String.format("Explore:  %.2f%n", npc.getGoalSystem().getUrgency("Explore",  state)));
        sb.append(String.format("Social:   %.2f%n", npc.getGoalSystem().getUrgency("Social",   state)));
        sb.append("\n");

        // ── Recent memory ─────────────────────────────────────────────
        sb.append("=== Recent Memory (last ").append(MAX_MEMORY_EVENTS).append(" events) ===\n");
        Deque<MemoryEvent> allEvents = npc.getMemory().getAllEvents();
        if (allEvents.isEmpty()) {
            sb.append("No notable events recorded yet.\n");
        } else {
            List<MemoryEvent> eventList = new ArrayList<>(allEvents);
            int start = Math.max(0, eventList.size() - MAX_MEMORY_EVENTS);
            for (int i = start; i < eventList.size(); i++) {
                MemoryEvent e = eventList.get(i);
                sb.append(String.format("  [tick %d] %s — %s%n",
                    e.getTick(), e.getType(), e.getDescription()));
            }
        }
        sb.append("\n");

        // ── Nearby NPCs ───────────────────────────────────────────────
        sb.append("=== Nearby NPCs (radius 3) ===\n");
        List<AbstractNPC> nearby = world.getNPCsNear(state.getLocation(), 3);
        nearby.removeIf(other -> other.getId().equals(npc.getId()));
        if (nearby.isEmpty()) {
            sb.append("None.\n");
        } else {
            for (AbstractNPC other : nearby) {
                double trust = npc.getMemory()
                    .getImpression(other.getId())
                    .map(imp -> imp.getTrust())
                    .orElse(0.5);
                int dist = other.getState().getLocation().distanceTo(state.getLocation());
                sb.append(String.format("  %s (trust: %.2f, distance: %d)%n",
                    other.getId(), trust, dist));
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
        sb.append("=== Available Strategies ===\n");
        sb.append("  GATHER_FOOD     - prioritise collecting food resources\n");
        sb.append("  SEEK_ALLIES     - move toward and interact with trusted NPCs\n");
        sb.append("  EXPLORE         - roam to discover resources and territory\n");
        sb.append("  AVOID_CONFLICT  - avoid hostile NPCs, minimise interaction\n");
        sb.append("  CONSERVE_ENERGY - rest and reduce movement to save energy\n");
        sb.append("  SURVIVE         - emergency mode: food and rest above all else\n");
        sb.append("\n");

        // ── Output format ─────────────────────────────────────────────
        sb.append("Choose the best strategy for the next 30-60 ticks.\n");
        sb.append("Respond ONLY with this JSON (no extra text, no markdown):\n");
        sb.append("{\n");
        sb.append("  \"strategy\": \"<one of the strategy names above>\",\n");
        sb.append("  \"intent\":   \"<what the NPC should achieve in one sentence>\",\n");
        sb.append("  \"target\":   \"<optional NPC id or zone to focus on, or empty string>\",\n");
        sb.append("  \"reason\":   \"<brief explanation of why this strategy fits the current situation>\"\n");
        sb.append("}");

        return sb.toString();
    }
}
