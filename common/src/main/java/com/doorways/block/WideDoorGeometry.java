package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.Facing;
import com.doorways.core.geometry.Motion;
import com.doorways.core.geometry.Swing;
import com.doorways.core.geometry.Vec2i;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The bridge between {@code :core}'s pure geometry and Minecraft's types.
 *
 * <p>The {@code core} module knows nothing of {@link Direction} or {@link BlockPos} -- that is
 * what makes it testable without the game (§9). All the translation happens here, and nowhere
 * else.
 */
public final class WideDoorGeometry {

    /** Converts Minecraft's horizontal direction into the geometry enum. */
    public static Facing toCore(Direction direction) {
        return switch (direction) {
            case NORTH -> Facing.NORTH;
            case EAST -> Facing.EAST;
            case SOUTH -> Facing.SOUTH;
            case WEST -> Facing.WEST;
            default -> throw new IllegalArgumentException("not a horizontal direction: " + direction);
        };
    }

    public static Direction toMinecraft(Facing facing) {
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
        };
    }

    /** Converts the blockstate value into the geometry enum. */
    public static Swing toCore(DoorSwing swing) {
        return switch (swing) {
            case CLOSED -> Swing.CLOSED;
            case OUT -> Swing.OUT;
            case BACK -> Swing.BACK;
        };
    }

    public static DoorSwing toMinecraft(Swing swing) {
        return switch (swing) {
            case CLOSED -> DoorSwing.CLOSED;
            case OUT -> DoorSwing.OUT;
            case BACK -> DoorSwing.BACK;
        };
    }

    /** Applies a horizontal geometry offset to a world position. */
    public static BlockPos offset(BlockPos origin, Vec2i delta) {
        return origin.offset(delta.x(), 0, delta.z());
    }

    /**
     * Column positions (lower half) for a given layout state.
     *
     * <p>{@code origin} is the position of column {@code PART 0} with the door closed.
     */
    public static List<BlockPos> columns(BlockPos origin, DoorLayout layout, Swing swing) {
        List<BlockPos> out = new ArrayList<>(layout.width());
        for (Vec2i o : layout.footprint(swing)) {
            out.add(offset(origin, o));
        }
        return out;
    }

    /** Positions that must be free in order to move from {@code from} to {@code to} (D-06). */
    public static List<BlockPos> newlyOccupied(BlockPos origin, DoorLayout layout,
                                               Swing from, Swing to) {
        List<BlockPos> out = new ArrayList<>();
        for (Vec2i o : layout.newlyOccupied(from, to)) {
            out.add(offset(origin, o));
        }
        return out;
    }

    /**
     * Reconstructs the structure origin from any part.
     *
     * <p>This is what lets the whole door be located with no block entity (§3).
     */
    public static BlockPos origin(BlockPos partPos, DoorLayout layout, int part, Swing swing) {
        Vec2i o = layout.offset(part, swing);
        return partPos.offset(-o.x(), 0, -o.z());
    }

    /**
     * The facing of the leaf plane that {@code part} belongs to, in the given state.
     *
     * <p>Closed, the leaf sits on the wall line and points along {@code FACING}. Open, it turns
     * 90°: clockwise when the hinge is at the leaf's low end, counter-clockwise when it is at
     * the high end. At width 1 this reduces exactly to vanilla {@code DoorBlock}'s rule.
     *
     * <p><b>Which way it swung does not enter into this.</b> A hinge does not move when the door
     * is pushed the other way, so the open leaf lies against the same end of its column either
     * way; what the two directions change is <i>where the columns are</i>, and that comes from
     * {@link DoorLayout#openOffset} rather than from here.
     *
     * <p>Deriving the rotation from the swing instead sends the leaf to the opposite end of its
     * column, and on a two-leaf door the halves then meet in the middle of the opening with
     * nothing holding them up.
     *
     * <p>One consequence worth knowing: at width 2 the leaf does not translate at all (D-07), so
     * the two swings are genuinely indistinguishable once open. The leaf lies in its own column
     * spanning the full depth of the block, and there is no room left to say which side of the
     * wall it travelled through. Vanilla makes the same compromise with every door it draws.
     */
    public static Direction leafDirection(DoorLayout layout, int part, Swing swing) {
        Direction facing = toMinecraft(layout.facing());
        // A sliding leaf never turns. It stays in the wall line whatever it is doing, and what
        // changes is which column holds it -- see DoorLayout.panelsAt.
        if (swing == Swing.CLOSED || layout.motion() == Motion.SLIDE) {
            return facing;
        }
        return layout.pivotAtLowEnd(part) ? facing.getClockWise() : facing.getCounterClockWise();
    }

    private WideDoorGeometry() {}
}
