package com.aiworld.model;

/**
 * A single episodic memory record stored in an NPC's Memory.
 * Events carry emotional valence (positive/negative) which shapes
 * future decisions — e.g., avoiding a hostile NPC seen at a location.
 *
 * {@code targetId} is set for all events that involve a specific other NPC:
 * WAS_ATTACKED, ATTACKED_NPC, SHARED_FOOD, STOLE_FOOD, WAS_ROBBED,
 * MET_NPC, HAD_CONVERSATION, and OBSERVED_DEATH (the NPC who was killed).
 * It is null only when no specific NPC is involved. Using a dedicated field
 * instead of parsing the description string makes retaliation targeting and
 * LLM context building robust to future description wording changes.
 */
public class MemoryEvent {

    public enum EventType {
        MET_NPC, WAS_ATTACKED, ATTACKED_NPC, SHARED_FOOD,
        STOLE_FOOD, WAS_ROBBED, HAD_CONVERSATION, OBSERVED_DEATH
    }

    private final long      tick;
    private final EventType type;
    private final String    description;
    private final Location  location;
    private final double    valence;     // -1.0 (very bad) to +1.0 (very good)
    private final String    targetId;    // NPC involved in this event, or null

    /** Constructor for social events that involve another NPC. */
    public MemoryEvent(long tick, EventType type, String description,
                       Location location, double valence, String targetId) {
        this.tick        = tick;
        this.type        = type;
        this.description = description;
        this.location    = location;
        this.valence     = valence;
        this.targetId    = targetId;
    }

    /** Constructor for impersonal events (no specific target NPC). */
    public MemoryEvent(long tick, EventType type, String description,
                       Location location, double valence) {
        this(tick, type, description, location, valence, null);
    }

    public long      getTick()        { return tick; }
    public EventType getType()        { return type; }
    public String    getDescription() { return description; }
    public Location  getLocation()    { return location; }
    public double    getValence()     { return valence; }
    /** Returns the ID of the other NPC involved, or null for impersonal events. */
    public String    getTargetId()    { return targetId; }

    @Override
    public String toString() {
        return "[T" + tick + "] " + type + " @ " + location
             + " (" + valence + "): " + description;
    }
}
