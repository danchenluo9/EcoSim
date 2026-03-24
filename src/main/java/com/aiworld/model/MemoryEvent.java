package com.aiworld.model;

/**
 * A single episodic memory record stored in an NPC's Memory.
 * Events carry emotional valence (positive/negative) which shapes
 * future decisions — e.g., avoiding a hostile NPC seen at a location.
 */
public class MemoryEvent {

    public enum EventType {
        SAW_RESOURCE, MET_NPC, WAS_ATTACKED, SHARED_FOOD,
        STOLE_FOOD, HAD_CONVERSATION, FOUND_SHELTER, OBSERVED_DEATH, FORMED_ALLIANCE
    }

    private final long      tick;        // world tick when this happened
    private final EventType type;
    private final String    description;
    private final Location  location;
    private final double    valence;     // -1.0 (very bad) to +1.0 (very good)

    public MemoryEvent(long tick, EventType type, String description,
                       Location location, double valence) {
        this.tick        = tick;
        this.type        = type;
        this.description = description;
        this.location    = location;
        this.valence     = valence;
    }

    public long      getTick()        { return tick; }
    public EventType getType()        { return type; }
    public String    getDescription() { return description; }
    public Location  getLocation()    { return location; }
    public double    getValence()     { return valence; }

    @Override
    public String toString() {
        return "[T" + tick + "] " + type + " @ " + location
             + " (" + valence + "): " + description;
    }
}
