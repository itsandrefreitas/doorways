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
 * The leaf rotates 90° about its hinge, towards {@code +FACING} -- away from whoever placed the
 * door (DECISIONS.md, D-04). A two-way hinge can also take it the other way, towards
 * {@code -FACING}; which of the two is {@link Swing}.
 *
 * <p>That yields a single formula covering all four widths, both modes and both directions:
 *
 * <pre>
 *   openOffset(part, swing) = R * hingePart + swingAxis(swing) * |part - hingePart|
 * </pre>
 *
 * The hinge column stays where it was; the rest unfold perpendicular to the wall, at whatever
 * distance they were from the hinge. Only the sign of the second term tells the two directions
 * apart, which is why one formula still covers both.
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
     * The direction the leaves travel in, for a given open state.
     *
     * <p>{@code FACING} is where the player was looking when placing the door, so
     * {@link Swing#OUT} means swinging <b>away from whoever placed it</b> -- into the opening,
     * not backwards. This was once inverted, and it only showed at widths 3 and 4: widths 1 and
     * 2 do not translate, the leaf rotates inside its own block and the error never surfaced.
     *
     * @throws IllegalArgumentException for {@link Swing#CLOSED}, which travels nowhere
     */
    public Facing swingAxis(Swing swing) {
        return switch (swing) {
            case OUT -> facing;
            case BACK -> facing.opposite();
            case CLOSED -> throw new IllegalArgumentException("a closed leaf has no swing axis");
        };
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

    public Vec2i openOffset(int part, Swing swing) {
        int pivot = hingePart(part);
        return wallAxis().vec().times(pivot)
                .plus(swingAxis(swing).vec().times(Math.abs(part - pivot)));
    }

    public Vec2i offset(int part, Swing swing) {
        return swing == Swing.CLOSED ? closedOffset(part) : openOffset(part, swing);
    }

    /** The occupied offsets, indexed by {@code PART}. */
    public List<Vec2i> footprint(Swing swing) {
        List<Vec2i> out = new ArrayList<>(width);
        for (int part = 0; part < width; part++) {
            out.add(offset(part, swing));
        }
        return List.copyOf(out);
    }

    /**
     * Positions that must be free in order to move from {@code from} to {@code to}.
     *
     * <p>Only the <b>new</b> ones: positions the door already occupies cannot block it from
     * itself (DECISIONS.md, D-06). An empty list means the transition can never be blocked.
     *
     * <p>Both ends are named because with three states {@code !target} no longer identifies
     * where the door is coming from.
     *
     * <p>This compares the two ends and nothing in between, which is sound only because every
     * transition the game performs has {@link Swing#CLOSED} at one end: a leaf swings out of its
     * frame or back into it. A door does not cross from {@link Swing#BACK} straight to
     * {@link Swing#OUT} -- it closes first -- and this method would not notice the frame it
     * swept through if it did.
     */
    public List<Vec2i> newlyOccupied(Swing from, Swing to) {
        Set<Vec2i> current = new LinkedHashSet<>(footprint(from));
        List<Vec2i> out = new ArrayList<>();
        for (Vec2i target : new LinkedHashSet<>(footprint(to))) {
            if (!current.contains(target)) {
                out.add(target);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Positions that stop being occupied on the same move.
     *
     * <p>Releasing on the way there is occupying on the way back, so this is the same question
     * with the ends swapped.
     */
    public List<Vec2i> released(Swing from, Swing to) {
        return newlyOccupied(to, from);
    }

    /**
     * Whether opening or closing actually moves blocks in the world.
     *
     * <p>False for widths 1 and 2: the leaf rotates inside its own block, exactly like a
     * vanilla door, and only the model and the collision change (DECISIONS.md, D-07).
     *
     * <p>The direction does not enter into it -- the two swings are mirror images, so either
     * both translate or neither does.
     */
    public boolean movesBlocks() {
        return !newlyOccupied(Swing.CLOSED, Swing.OUT).isEmpty();
    }

    /**
     * The inverse of {@link #offset}: given a part's position and state, returns the
     * structure's origin.
     *
     * <p>This is what lets any part locate the others with no block entity (§3).
     */
    public Vec2i origin(Vec2i partPosition, int part, Swing swing) {
        return partPosition.minus(offset(part, swing));
    }

    /** Absolute positions of every column, given the origin. */
    public List<Vec2i> columnsAt(Vec2i origin, Swing swing) {
        List<Vec2i> out = new ArrayList<>(width);
        for (Vec2i o : footprint(swing)) {
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
