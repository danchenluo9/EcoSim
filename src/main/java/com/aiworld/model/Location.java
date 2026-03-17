package com.aiworld.model;

/**
 * Represents a 2D grid coordinate in the world.
 * Can be extended to 3D or continuous space later.
 */
public class Location {

    private int x;
    private int y;

    public Location(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Manhattan distance — sufficient for grid-based pathfinding. */
    public int distanceTo(Location other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /** Returns a new Location stepped one unit toward the target. */
    public Location stepToward(Location target) {
        int dx = Integer.compare(target.x, this.x);
        int dy = Integer.compare(target.y, this.y);
        // Move on x-axis first, then y-axis
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
        return 31 * x + y;
    }
}
