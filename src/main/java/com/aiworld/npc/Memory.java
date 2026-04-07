package com.aiworld.npc;

import com.aiworld.dialog.DialogSession;
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

    private static final int    MAX_EVENTS        = 50;  // rolling episodic buffer
    private static final int    MAX_CONVERSATIONS = 20;  // rolling conversation log
    private static final double DECAY_RATE        = 0.005; // trust decay per tick

    /** Max MET_NPC events kept in the buffer.
     *  In dense clusters each NPC generates ~4 MET_NPC events per 10 ticks.
     *  Capping them prevents low-signal "Spotted X" entries from evicting high-signal
     *  combat/theft events that the LLM and cooldown logic depend on. */
    private static final int MAX_MET_NPC_EVENTS = 10;

    /** Chronological episodic event buffer. */
    private final Deque<MemoryEvent> events = new ArrayDeque<>();

    /** Index for O(1) lookup and O(1) eviction of events by type. */
    private final Map<MemoryEvent.EventType, ArrayDeque<MemoryEvent>> eventIndex = new HashMap<>();

    /** Social model: NPC id → subjective impression. */
    private final Map<String, NPCImpression> impressions = new HashMap<>();

    /** Conversation log: chronological record of dialog sessions. */
    private final ArrayDeque<DialogSession> conversationLog = new ArrayDeque<>();

    // ── Event log ────────────────────────────────────────────────────

    /** Appends a new event, evicting the oldest if at capacity.
     *  MET_NPC events are capped independently: if the bucket is full, the new
     *  event is dropped rather than evicting an older (higher-signal) event. */
    public void addEvent(MemoryEvent event) {
        if (event.getType() == MemoryEvent.EventType.MET_NPC) {
            ArrayDeque<MemoryEvent> metBucket = eventIndex.get(MemoryEvent.EventType.MET_NPC);
            if (metBucket != null && metBucket.size() >= MAX_MET_NPC_EVENTS) return;
        }
        if (events.size() >= MAX_EVENTS) {
            MemoryEvent evicted = events.pollFirst();
            if (evicted != null) {
                ArrayDeque<MemoryEvent> bucket = eventIndex.get(evicted.getType());
                if (bucket != null) {
                    bucket.pollFirst(); // O(1): evicted is always the oldest = first in bucket
                    if (bucket.isEmpty()) eventIndex.remove(evicted.getType()); // don't accumulate empty buckets
                }
            }
        }
        events.addLast(event);
        eventIndex.computeIfAbsent(event.getType(), k -> new ArrayDeque<>()).addLast(event);
    }

    /**
     * Returns all events of a given type — O(1) index lookup.
     *
     * Returns an unmodifiable view (no copy) so callers can stream cheaply.
     * All callers only read (stream/anyMatch/count); none mutate the returned collection.
     */
    public Collection<MemoryEvent> getEventsOfType(MemoryEvent.EventType type) {
        ArrayDeque<MemoryEvent> bucket = eventIndex.get(type);
        if (bucket == null || bucket.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableCollection(bucket);
    }

    public Deque<MemoryEvent> getAllEvents() { return new ArrayDeque<>(events); }

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

    public Map<String, NPCImpression> getAllImpressions() { return Collections.unmodifiableMap(impressions); }

    /** Removes impressions for dead NPCs so living NPCs don't carry stale social data. */
    public void cleanImpressions(Set<String> deadIds) {
        impressions.keySet().removeAll(deadIds);
    }

    // ── Conversation log ──────────────────────────────────────────────

    public void addConversation(DialogSession session) {
        if (conversationLog.size() >= MAX_CONVERSATIONS) {
            conversationLog.pollFirst();
        }
        conversationLog.addLast(session);
    }

    /** Returns the most recent {@code n} conversations. */
    public List<DialogSession> getRecentConversations(int n) {
        List<DialogSession> all = new ArrayList<>(conversationLog);
        int start = Math.max(0, all.size() - n);
        return all.subList(start, all.size());
    }
}
