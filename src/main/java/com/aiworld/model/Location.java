package com.aiworld.model;

import java.util.Objects;
import java.util.Random;

/**
 * Represents a 2D grid coordinate in the world.
 * Can be extended to 3D or continuous space later.
 */
public class Location {

    // Shared RNG for axis selection in stepToward — not security-sensitive,
    // only used to break movement symmetry so NPCs don't all follow identical paths.
    private static final Random RANDOM = new Random();

    private final int x;
    private final int y;

    public Location(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Manhattan distance — sufficient for grid-based pathfinding. */
    public int distanceTo(Location other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /** Returns a new Location stepped one unit toward the target.
     *
     * When movement is possible on both axes, picks randomly between them so
     * NPCs heading for the same destination don't all walk the identical path
     * (which created single-file columns toward every resource cluster).
     */
    public Location stepToward(Location target) {
        if (target == null) throw new IllegalArgumentException("stepToward: target must not be null");
        int dx = Integer.compare(target.x, this.x);
        int dy = Integer.compare(target.y, this.y);
        if (dx != 0 && dy != 0) {
            // Both axes have progress to make — choose randomly to vary paths
            return RANDOM.nextBoolean()
                ? new Location(this.x + dx, this.y)
                : new Location(this.x, this.y + dy);
        }
        if (dx != 0) return new Location(this.x + dx, this.y);
        return new Location(this.x, this.y + dy);
    }

    public int getX() { return x; }
    public int getY() { return y; }

    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Location)) return false;
        Location o = (Location) obj;
        return this.x == o.x && this.y == o.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}
