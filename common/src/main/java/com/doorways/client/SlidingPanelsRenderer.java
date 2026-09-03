package com.doorways.client;

import com.doorways.block.DoorSwing;
import com.doorways.block.SlidingPanelsBlockEntity;
import com.doorways.block.WideDoorBlock;
import com.doorways.block.WideDoorGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a sliding door's panels, including while they are on their way.
 *
 * <p>Copied in shape from vanilla's {@code PistonHeadRenderer}, which solves the same problem: a
 * block that has to appear somewhere other than where its blockstate says it is. Both use
 * {@link MovingBlockRenderState}, which carries a blockstate plus the lighting of the place it
 * is drawn at, so a displaced panel is lit like its surroundings rather than like the block it
 * came from.
 *
 * <p>For most sliding doors it draws <b>only while a panel is travelling</b>. A door at rest is
 * an ordinary block and draws itself; {@code SlidingDoorBlock.SLIDING} hands the job over for the
 * third of a second between, and takes it back afterwards. Drawing every sliding door here always
 * was the first attempt: simpler, and it made them disappear past 64 blocks, which is as far as a
 * block entity renderer reaches by default -- hence {@link #getViewDistance()}.
 *
 * <p>The exception is the see-through one, which is drawn here at rest as well: a handover is
 * only invisible while both sides draw the same thing, and through glass they do not. See
 * {@code DoorStyle.drawnByRenderer()}, which is where that is argued.
 *
 * <p>Nothing here is about doors. It draws one panel per block entity, at whatever offset that
 * block entity reports, which is the shape a larger moving thing would want too.
 */
public class SlidingPanelsRenderer
        implements BlockEntityRenderer<SlidingPanelsBlockEntity, SlidingPanelsRenderState> {

    /**
     * How far away this still draws, in blocks. The default is 64.
     *
     * <p>A resting door is drawn by its own model and is visible as far as any block, but a
     * <b>moving</b> one is drawn only here -- so past 64 blocks a sliding door vanished for the
     * third of a second it was on its way, and reappeared open.
     *
     * <p>512 rather than the beacon's 256, because a see-through door is drawn here at all
     * times: for that one, this number is not how far it animates but how far it exists.
     */
    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public SlidingPanelsRenderState createRenderState() {
        return new SlidingPanelsRenderState();
    }

    @Override
    public void extractRenderState(SlidingPanelsBlockEntity door, SlidingPanelsRenderState state,
                                   float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(
                door, state, partialTicks, cameraPosition, breakProgress);
        state.panel = null;
        state.xOffset = 0.0F;
        state.zOffset = 0.0F;
        state.breakingParts.clear();

        BlockState blockState = door.getBlockState();
        if (!(door.getLevel() instanceof ClientLevel level)
                || !(blockState.getBlock() instanceof WideDoorBlock block)
                || !door.isDrawnHere()) {
            return;
        }

        // The offset is a signed count of columns; the wall axis turns it into a direction.
        float offset = door.panelOffset(partialTicks);
        Direction wall = WideDoorGeometry.toMinecraft(block.layoutOf(blockState).wallAxis());
        state.xOffset = wall.getStepX() * offset;
        state.zOffset = wall.getStepZ() * offset;

        BlockPos pos = door.getBlockPos();
        MovingBlockRenderState panel = new MovingBlockRenderState();
        panel.randomSeedPos = pos;
        panel.blockPos = pos;
        // The shut state is each column's own single panel, on its own track. The open one
        // would be the whole leaf stacked in the parking column, and drawing that from every
        // column would give one panel per column too many.
        panel.blockState = blockState.setValue(block.swingProperty(), DoorSwing.CLOSED);
        panel.biome = level.getBiome(pos);
        panel.cardinalLighting = level.cardinalLighting();
        panel.lightEngine = level.getLightEngine();
        state.panel = panel;

        // A block drawn by a renderer has to draw its own cracks: the game spreads them over
        // the chunk's mesh, and this panel is not in it. Without this the door still broke in
        // the same time and dropped the same item, but nothing on it showed that it was being
        // broken -- and a block that gives no answer to being hit reads as a block that cannot
        // be broken at all.
        //
        // Only while someone is actually mining it, which is the only time the pieces are worth
        // collecting.
        if (state.breakProgress != null) {
            collectParts(panel.blockState, pos, state.breakingParts);
        }
    }

    /**
     * The pieces a blockstate's model is made of, in the same way the game collects them to
     * crack an ordinary block ({@code LevelRenderer}, block-breaking pass).
     *
     * <p>The seed comes from the position, so a model that varies at random stays the same block
     * it was drawing a moment ago. NeoForge deprecates this in favour of its own model
     * extensions; vanilla itself has no other way to ask.
     */
    @SuppressWarnings("deprecation")
    private static void collectParts(BlockState state, BlockPos pos,
                                     List<BlockStateModelPart> into) {
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
                .collectParts(RandomSource.create(state.getSeed(pos)), into);
    }

    @Override
    public void submit(SlidingPanelsRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.panel == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(state.xOffset, 0.0F, state.zOffset);
        collector.submitMovingBlock(poseStack, state.panel, 0);
        // Inside the same translation, so the cracks travel with the panel rather than staying
        // behind on the block the panel came from.
        if (state.breakProgress != null && !state.breakingParts.isEmpty()) {
            collector.submitBreakingBlockModel(
                    poseStack, state.breakingParts, state.breakProgress.progress());
        }
        poseStack.popPose();
    }
}
