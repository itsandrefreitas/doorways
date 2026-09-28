package com.doorways.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.PushReaction;

/**
 * The spellings that differ between the game versions this mod builds for -- <b>26.2</b>.
 *
 * <p>There is one of these per version, under {@code src/main/java-<version>}, and the build
 * puts exactly one of them on the source path. Everything else in the mod is written once and
 * compiles against both games unchanged.
 *
 * <p><b>Keep this file small.</b> It is the measure of how far the two versions have drifted,
 * and it only works while each difference is a single expression that can be given a name. The
 * moment a difference cannot be -- a method that has to stop overriding something, a class that
 * has to implement a different interface, an import that no longer exists on one side -- this
 * approach is finished, and the answer is a preprocessor. That is written down in DECISIONS.md,
 * D-41, together with what to reach for.
 */
public final class Vanilla {

    private Vanilla() {
    }

    /**
     * What a piston does to a block it cannot move: pops it off as an item.
     *
     * <p>26.3 renamed the constant to {@code POPPED}, which says what happens rather than how it
     * looks. The newer name is the one used throughout this mod.
     */
    public static final PushReaction POPPED = PushReaction.DESTROY;

    /**
     * Submits the cracks spreading over a block that is being mined.
     *
     * <p>26.3 added a flag for whether the block is see-through, which changes how the overlay
     * is composited. 26.2 works it out for itself, so the argument is dropped here.
     */
    public static void submitBreaking(SubmitNodeCollector collector, PoseStack poseStack,
                                      List<BlockStateModelPart> parts, int stage,
                                      boolean translucent) {
        collector.submitBreakingBlockModel(poseStack, parts, stage);
    }

    /**
     * Turns the stack about an axis, in degrees.
     *
     * <p>26.3 named this {@code rotateDegrees} and took the quaternion step out of the caller's
     * hands; here it is still one built and multiplied in.
     */
    public static void rotateDegrees(PoseStack poseStack, Axis axis, float degrees) {
        poseStack.mulPose(axis.rotationDegrees(degrees));
    }

    /**
     * The positions within a Manhattan distance of one, nearest first.
     *
     * <p>26.2 takes a range per axis and walks a box; 26.3 takes the Manhattan radius itself.
     * Passing the same range three times gives the box that contains the octahedron, which is a
     * superset -- so the caller's own {@code distManhattan} guard is what makes the two agree,
     * and it has to stay.
     */
    public static Iterable<BlockPos> withinManhattan(BlockPos pos, int distance) {
        return BlockPos.withinManhattan(pos, distance, distance, distance);
    }
}
