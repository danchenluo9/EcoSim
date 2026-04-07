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
                    // [MEM-1] Invariant: every event in `events` has exactly one matching entry
                    // at the head of its eventIndex bucket, in the same chronological order.
                    // This holds because addEvent appends to BOTH structures atomically, and the
                    // MET_NPC cap returns before either append (so dropped events appear in neither).
                    // If this throws, a code path appended to one structure without the other.
                    MemoryEvent bucketHead = bucket.peekFirst();
                    if (bucketHead != evicted) {
                        throw new IllegalStateException(
                            "Memory eviction invariant violated: events deque head " + evicted
                            + " does not match eventIndex bucket head " + bucketHead
                            + " for type " + evicted.getType());
                    }
                    bucket.pollFirst(); // O(1): evicted is always the oldest = first in bucket
                    if (bucket.isEmpty()) eventIndex.remove(evicted.getType()); // don't accumulate empty buckets
                } else {
                    // [MEM-3] bucket == null means the evicted event was present in `events` but
                    // absent from `eventIndex` — the other half of the desync MEM-1 guards against.
                    // addEvent() always appends to both structures or neither, so this branch is
                    // unreachable with correct code. If it fires, a future code path broke the
                    // invariant (e.g., appending to `events` without updating `eventIndex`).
                    throw new IllegalStateException(
                        "Memory eviction invariant violated: no eventIndex bucket for evicted event "
                        + evicted + " of type " + evicted.getType());
                }
            }
        }
        events.addLast(event);
        eventIndex.computeIfAbsent(event.getType(), k -> new ArrayDeque<>()).addLast(event);
    }

    /**
     * Returns all events of a given type — O(1) index lookup, snapshot copy.
     *
     * [MEM-2] Returns a defensive snapshot rather than a live view. The previous
     * unmodifiableCollection(bucket) was safe only because all callers streamed it
     * synchronously on the world-loop thread. A snapshot prevents silent corruption
     * if a future caller holds the reference across a tick boundary or passes it to
     * a background thread, where bucket mutations (addEvent/eviction) would cause
     * ConcurrentModificationException or stale iteration. Buckets hold ≤50 entries;
     * the copy cost is negligible compared to any LLM I/O this drives.
     */
    public Collection<MemoryEvent> getEventsOfType(MemoryEvent.EventType type) {
        ArrayDeque<MemoryEvent> bucket = eventIndex.get(type);
        if (bucket == null || bucket.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(bucket);
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
