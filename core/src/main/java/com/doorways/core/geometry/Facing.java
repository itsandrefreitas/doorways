package com.doorways.core.geometry;

/**
 * A horizontal direction, independent of Minecraft.
 *
 * <p>Minecraft's axes: X grows east, Z grows south. Seen from above with X to the right and Z
 * downwards, {@link #clockwise()} runs N → E → S → W.
 */
public enum Facing {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    public final int dx;
    public final int dz;

    Facing(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public Vec2i vec() {
        return new Vec2i(dx, dz);
    }

    public Facing clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }

    public Facing counterClockwise() {
        return clockwise().clockwise().clockwise();
    }

    public Facing opposite() {
        return clockwise().clockwise();
    }
}
