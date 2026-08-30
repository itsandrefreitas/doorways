package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.Facing;
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

    /** Applies a horizontal geometry offset to a world position. */
    public static BlockPos offset(BlockPos origin, Vec2i delta) {
        return origin.offset(delta.x(), 0, delta.z());
    }

    /**
     * Column positions (lower half) for a given layout state.
     *
     * <p>{@code origin} is the position of column {@code PART 0} with the door closed.
     */
    public static List<BlockPos> columns(BlockPos origin, DoorLayout layout, boolean open) {
        List<BlockPos> out = new ArrayList<>(layout.width());
        for (Vec2i o : layout.footprint(open)) {
            out.add(offset(origin, o));
        }
        return out;
    }

    /** Positions that must be free in order to move to {@code targetOpen} (D-06). */
    public static List<BlockPos> newlyOccupied(BlockPos origin, DoorLayout layout, boolean targetOpen) {
        List<BlockPos> out = new ArrayList<>();
        for (Vec2i o : layout.newlyOccupied(targetOpen)) {
            out.add(offset(origin, o));
        }
        return out;
    }

    /**
     * Reconstructs the structure origin from any part.
     *
     * <p>This is what lets the whole door be located with no block entity (§3).
     */
    public static BlockPos origin(BlockPos partPos, DoorLayout layout, int part, boolean open) {
        Vec2i o = layout.offset(part, open);
        return partPos.offset(-o.x(), 0, -o.z());
    }

    /**
     * The facing of the leaf plane that {@code part} belongs to, in the given state.
     *
     * <p>Closed, the leaf sits on the wall line and points along {@code FACING}. Open, it turns
     * 90°: clockwise when the hinge is at the leaf's low end, counter-clockwise when it is at
     * the high end. At width 1 this reduces exactly to vanilla {@code DoorBlock}'s rule.
     */
    public static Direction leafDirection(DoorLayout layout, int part, boolean open) {
        Direction facing = toMinecraft(layout.facing());
        if (!open) {
            return facing;
        }
        return layout.pivotAtLowEnd(part) ? facing.getClockWise() : facing.getCounterClockWise();
    }

    private WideDoorGeometry() {}
}
