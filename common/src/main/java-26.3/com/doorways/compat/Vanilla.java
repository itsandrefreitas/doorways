package com.doorways.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.PushReaction;

/**
 * The spellings that differ between the game versions this mod builds for -- <b>26.3</b>.
 *
 * <p>See the 26.2 copy of this file for what belongs here and what does not. The two must
 * always declare the same members, or the mod compiles on one version and not the other.
 */
public final class Vanilla {

    private Vanilla() {
    }

    /**
     * What a piston does to a block it cannot move: pops it off as an item.
     *
     * <p>26.2 called this {@code DESTROY}. Same value, clearer name.
     */
    public static final PushReaction POPPED = PushReaction.POPPED;

    /**
     * Submits the cracks spreading over a block that is being mined.
     *
     * <p>26.3 wants to be told whether the block is see-through: with order-independent
     * transparency it can no longer infer it from the render type at this point.
     */
    public static void submitBreaking(SubmitNodeCollector collector, PoseStack poseStack,
                                      List<BlockStateModelPart> parts, int stage,
                                      boolean translucent) {
        collector.submitBreakingBlockModel(poseStack, parts, stage, translucent);
    }

    /**
     * Turns the stack about an axis, in degrees.
     *
     * <p>{@code mulPose(Quaternionfc)} is gone: turning is now {@code rotate}, and there is an
     * overload that takes the axis and the angle and builds the quaternion itself.
     */
    public static void rotateDegrees(PoseStack poseStack, Axis axis, float degrees) {
        poseStack.rotateDegrees(axis, degrees);
    }

    /**
     * The positions within a Manhattan distance of one, nearest first.
     *
     * <p>26.2 wanted a range per axis and walked the box around the octahedron; this takes the
     * Manhattan radius itself and yields exactly the octahedron.
     */
    public static Iterable<BlockPos> withinManhattan(BlockPos pos, int distance) {
        return BlockPos.withinManhattan(pos, distance);
    }
}
