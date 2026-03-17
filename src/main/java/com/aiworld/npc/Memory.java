package com.aiworld.npc;

import com.aiworld.model.MemoryEvent;
import com.aiworld.model.NPCImpression;

import java.util.*;

/**
 * Memory gives an NPC an episodic history and a social model of the world.
 *
 * Two components:
 *  1. Event log   — recent things the NPC witnessed (with recency decay)
 *  2. Impressions — per-NPC trust/hostility map (drives social decisions)
 *
 * Memory capacity is bounded to simulate cognitive limits and force
 * NPCs to "forget" old events — creating realistic behavioral drift.
 */
public class Memory {

    private static final int    MAX_EVENTS  = 50;    // rolling episodic buffer
    private static final double DECAY_RATE  = 0.005; // trust decay per tick

    /** Chronological episodic event buffer. */
    private final Deque<MemoryEvent> events = new ArrayDeque<>();

    /** Social model: NPC id → subjective impression. */
    private final Map<String, NPCImpression> impressions = new HashMap<>();

    // ── Event log ────────────────────────────────────────────────────

    /** Appends a new event, evicting the oldest if at capacity. */
    public void addEvent(MemoryEvent event) {
        if (events.size() >= MAX_EVENTS) {
            events.pollFirst();
        }
        events.addLast(event);
    }

    /** Returns all events of a given type (e.g. to recall all hostile encounters). */
    public List<MemoryEvent> getEventsOfType(MemoryEvent.EventType type) {
        List<MemoryEvent> result = new ArrayList<>();
        for (MemoryEvent e : events) {
            if (e.getType() == type) result.add(e);
        }
        return result;
    }

    public Deque<MemoryEvent> getAllEvents() { return events; }

    // ── Impressions ──────────────────────────────────────────────────

    public Optional<NPCImpression> getImpression(String npcId) {
        return Optional.ofNullable(impressions.get(npcId));
    }

    public NPCImpression getOrCreateImpression(String npcId) {
        return impressions.computeIfAbsent(npcId, NPCImpression::new);
    }

    public void recordPositiveInteraction(String npcId, double delta) {
        getOrCreateImpression(npcId).recordPositiveInteraction(delta);
    }

    public void recordNegativeInteraction(String npcId, double delta) {
        getOrCreateImpression(npcId).recordNegativeInteraction(delta);
    }

    /**
     * Called each tick to decay trust values toward neutral.
     * NPCs that haven't interacted in a while become strangers again.
     */
    public void decayImpressions() {
        for (NPCImpression impression : impressions.values()) {
            impression.decayOverTime(DECAY_RATE);
        }
    }

    public Map<String, NPCImpression> getAllImpressions() { return impressions; }
}
