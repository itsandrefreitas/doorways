package com.doorways.core.geometry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The geometry of an articulated door. Pure, deterministic, with no dependency on Minecraft --
 * this is the piece §9 of the specification wants isolated and testable without rendering.
 *
 * <h2>Coordinate system</h2>
 * All offsets are horizontal and relative to the <b>origin</b>: the position of column
 * {@code PART 0} with the door <b>closed</b>. Height is always 2 and never enters the maths.
 *
 * <p>{@code R = facing.clockwise()} is the axis along which {@code PART} grows -- the wall
 * line. Closed, column {@code i} sits at {@code R * i}.
 *
 * <h2>Opening rule</h2>
 * The leaf rotates 90° about its hinge and <b>always</b> swings towards {@code +FACING}, away
 * from whoever placed the door (DECISIONS.md, D-04).
 *
 * <p>That yields a single formula covering all four widths and both modes:
 *
 * <pre>
 *   openOffset(part) = R * hingePart + FACING * |part - hingePart|
 * </pre>
 *
 * The hinge column stays where it was; the rest unfold perpendicular to the wall, at whatever
 * distance they were from the hinge.
 */
public record DoorLayout(Facing facing, int width, DoorMode mode, Hinge hinge) {

    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 4;

    public DoorLayout {
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw new IllegalArgumentException("width must be between 1 and 4, was " + width);
        }
        if (mode == DoorMode.SPLIT && width % 2 != 0) {
            throw new IllegalArgumentException("SPLIT requires an even width, was " + width);
        }
    }

    /** A layout using the default mode for that width (the table in §2). */
    public static DoorLayout of(Facing facing, int width, Hinge hinge) {
        return new DoorLayout(facing, width, DoorMode.defaultFor(width), hinge);
    }

    /** The wall axis: the direction in which the {@code PART} index grows. */
    public Facing wallAxis() {
        return facing.clockwise();
    }

    /**
     * The direction the leaves swing towards.
     *
     * <p>{@code FACING} is where the player was looking when placing the door, so swinging
     * towards {@code +FACING} means swinging <b>away from whoever placed it</b> -- into the
     * opening, not backwards. This was once inverted, and it only showed at widths 3 and 4:
     * widths 1 and 2 do not translate, the leaf rotates inside its own block and the error
     * never surfaced.
     */
    public Facing swingAxis() {
        return facing;
    }

    /**
     * Index of the column acting as the hinge for the leaf {@code part} belongs to.
     *
     * <p>Under {@link DoorMode#SPLIT} each half hinges at its outer end, so {@link #hinge} is
     * never consulted.
     */
    public int hingePart(int part) {
        return pivotAtLowEnd(part) ? leafStart(part) : leafEnd(part);
    }

    /**
     * Whether this leaf's pivot sits at its <b>low</b> end (the {@code -R} side).
     *
     * <p>This is the question that decides the direction of rotation, not
     * {@code hingePart() == 0}. On a width-1 door both ends are the same column, so the pivot
     * index is always 0 and cannot tell the two hinges apart -- the leaf always rotated the
     * same way.
     */
    public boolean pivotAtLowEnd(int part) {
        requirePart(part);
        return switch (mode) {
            case SINGLE -> hinge == Hinge.LEFT;
            case SPLIT -> part < width / 2;
        };
    }

    /** Index of the first column of the leaf {@code part} belongs to. */
    public int leafStart(int part) {
        requirePart(part);
        return mode == DoorMode.SINGLE || part < width / 2 ? 0 : width / 2;
    }

    /** Index of the last column of the leaf {@code part} belongs to. */
    public int leafEnd(int part) {
        requirePart(part);
        return mode == DoorMode.SINGLE || part >= width / 2 ? width - 1 : width / 2 - 1;
    }

    public Vec2i closedOffset(int part) {
        requirePart(part);
        return wallAxis().vec().times(part);
    }

    public Vec2i openOffset(int part) {
        int pivot = hingePart(part);
        return wallAxis().vec().times(pivot)
                .plus(swingAxis().vec().times(Math.abs(part - pivot)));
    }

    public Vec2i offset(int part, boolean open) {
        return open ? openOffset(part) : closedOffset(part);
    }

    /** The occupied offsets, indexed by {@code PART}. */
    public List<Vec2i> footprint(boolean open) {
        List<Vec2i> out = new ArrayList<>(width);
        for (int part = 0; part < width; part++) {
            out.add(offset(part, open));
        }
        return List.copyOf(out);
    }

    public List<Vec2i> closedFootprint() {
        return footprint(false);
    }

    public List<Vec2i> openFootprint() {
        return footprint(true);
    }

    /**
     * Positions that must be free in order to move to {@code targetOpen}.
     *
     * <p>Only the <b>new</b> ones: positions the door already occupies cannot block it from
     * itself (DECISIONS.md, D-06). An empty list means the transition can never be blocked.
     */
    public List<Vec2i> newlyOccupied(boolean targetOpen) {
        Set<Vec2i> current = new LinkedHashSet<>(footprint(!targetOpen));
        List<Vec2i> out = new ArrayList<>();
        for (Vec2i target : new LinkedHashSet<>(footprint(targetOpen))) {
            if (!current.contains(target)) {
                out.add(target);
            }
        }
        return List.copyOf(out);
    }

    /** Positions that stop being occupied when moving to {@code targetOpen}. */
    public List<Vec2i> released(boolean targetOpen) {
        Set<Vec2i> target = new LinkedHashSet<>(footprint(targetOpen));
        List<Vec2i> out = new ArrayList<>();
        for (Vec2i current : new LinkedHashSet<>(footprint(!targetOpen))) {
            if (!target.contains(current)) {
                out.add(current);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Whether opening or closing actually moves blocks in the world.
     *
     * <p>False for widths 1 and 2: the leaf rotates inside its own block, exactly like a
     * vanilla door, and only the model and the collision change (DECISIONS.md, D-07).
     */
    public boolean movesBlocks() {
        return !newlyOccupied(true).isEmpty();
    }

    /**
     * The inverse of {@link #offset}: given a part's position and state, returns the
     * structure's origin.
     *
     * <p>This is what lets any part locate the others with no block entity (§3).
     */
    public Vec2i origin(Vec2i partPosition, int part, boolean open) {
        return partPosition.minus(offset(part, open));
    }

    /** Absolute positions of every column, given the origin. */
    public List<Vec2i> columnsAt(Vec2i origin, boolean open) {
        List<Vec2i> out = new ArrayList<>(width);
        for (Vec2i o : footprint(open)) {
            out.add(origin.plus(o));
        }
        return List.copyOf(out);
    }

    private void requirePart(int part) {
        if (part < 0 || part >= width) {
            throw new IllegalArgumentException("PART " + part + " outside the range 0.." + (width - 1));
        }
    }
}
