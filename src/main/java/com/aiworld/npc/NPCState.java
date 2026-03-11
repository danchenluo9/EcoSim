package com.aiworld.npc;

import com.aiworld.model.Location;

/**
 * NPCState holds all mutable physical attributes of an NPC.
 *
 * Separating state from behavior (NPCState vs AbstractNPC) allows:
 * - Clean serialization for save/load
 * - Easy state cloning for look-ahead planning (future AI extension)
 * - Clear contract for what actions are allowed to modify
 */
public class NPCState {

    // ── Vital stats ──────────────────────────────────────────────────
    private int energy;      // 0–100; depleted by movement and action
    private int food;        // 0–100; depleted over time, restored by gathering
    private int health;      // 0–100; reduced by starvation/attacks
    private int age;         // ticks alive — enables aging mechanics later

    private final int maxEnergy;
    private final int maxFood;
    private final int maxHealth;

    // ── Position ─────────────────────────────────────────────────────
    private Location location;

    public NPCState(int energy, int food, int health, Location location) {
        this.maxEnergy = 100;
        this.maxFood   = 100;
        this.maxHealth = 100;
        this.energy    = Math.min(energy, maxEnergy);
        this.food      = Math.min(food, maxFood);
        this.health    = Math.min(health, maxHealth);
        this.location  = location;
        this.age       = 0;
    }

    // ── Tick-level passive effects ────────────────────────────────────

    /**
     * Called each tick to apply passive stat changes:
     * food depletes from metabolism, starvation damages health.
     */
    public void passiveTick() {
        age++;
        food   = Math.max(0, food - 1);    // metabolism
        energy = Math.max(0, energy - 1);  // idle energy drain

        if (food == 0) {
            health = Math.max(0, health - 3); // starvation damage
        }
    }

    // ── Mutators ─────────────────────────────────────────────────────

    public void depleteEnergy(int amount) { energy = Math.max(0, energy - amount); }
    public void restoreEnergy(int amount) { energy = Math.min(maxEnergy, energy + amount); }

    public void depleteFood(int amount)   { food = Math.max(0, food - amount); }
    public void addFood(int amount)       { food = Math.min(maxFood, food + amount); }

    public void damageHealth(int amount)  { health = Math.max(0, health - amount); }
    public void restoreHealth(int amount) { health = Math.min(maxHealth, health + amount); }

    public void setLocation(Location location) { this.location = location; }

    // ── Accessors ────────────────────────────────────────────────────

    public int      getEnergy()   { return energy; }
    public int      getFood()     { return food; }
    public int      getHealth()   { return health; }
    public int      getAge()      { return age; }
    public Location getLocation() { return location; }

    // Ratio helpers (0.0–1.0) used by Goal urgency computations
    public double getEnergyRatio() { return (double) energy / maxEnergy; }
    public double getFoodRatio()   { return (double) food   / maxFood; }
    public double getHealthRatio() { return (double) health / maxHealth; }

    public boolean isDead() { return health <= 0; }

    @Override
    public String toString() {
        return String.format("State{E:%d F:%d H:%d @%s}", energy, food, health, location);
    }
}
