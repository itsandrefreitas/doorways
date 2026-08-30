package com.doorways.core.geometry;

/** A horizontal offset in blocks. Height never enters the geometry: a door is always 2 tall. */
public record Vec2i(int x, int z) {

    public static final Vec2i ZERO = new Vec2i(0, 0);

    public Vec2i plus(Vec2i other) {
        return new Vec2i(x + other.x, z + other.z);
    }

    public Vec2i minus(Vec2i other) {
        return new Vec2i(x - other.x, z - other.z);
    }

    public Vec2i times(int scalar) {
        return new Vec2i(x * scalar, z * scalar);
    }

    @Override
    public String toString() {
        return "(" + x + "," + z + ")";
    }
}
