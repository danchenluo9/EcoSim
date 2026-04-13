package com.aiworld.model;

/**
 * A gatherable resource placed at a world location (food, wood, etc.).
 * Resources are finite and regenerate over time — enabling competition
 * and emergent NPC migration toward abundant areas.
 */
public class Resource {

    public enum Type { FOOD, MEDICINE }

    private final Type     type;
    private final Location location;
    private int            quantity;
    private final int      regenRate;   // units restored per tick
    private final int      maxQuantity;

    public Resource(Type type, Location location, int initialQuantity,
                    int maxQuantity, int regenRate) {
        this.type        = type;
        this.location    = location;
        this.quantity    = initialQuantity;
        this.maxQuantity = maxQuantity;
        this.regenRate   = regenRate;
    }

    /**
     * Called each world tick — replenishes supply up to the cap.
     * Enables sustainable ecosystems and resource competition dynamics.
     */
    public void tick() {
        quantity = Math.min(maxQuantity, quantity + regenRate);
    }

    /**
     * Attempts to consume up to {@code amount} units.
     * @return actual amount consumed (may be less if nearly depleted).
     */
    public int consume(int amount) {
        int consumed = Math.min(quantity, amount);
        quantity -= consumed;
        return consumed;
    }

    public boolean isDepleted()    { return quantity <= 0; }
    public Type     getType()      { return type; }
    public Location getLocation()  { return location; }
    public int      getQuantity()  { return quantity; }
    public int      getMaxQuantity(){ return maxQuantity; }
}
