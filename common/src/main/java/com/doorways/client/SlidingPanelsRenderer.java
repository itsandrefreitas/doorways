package com.doorways.client;

import com.doorways.block.DoorPattern;
import com.doorways.block.DoorSwing;
import com.doorways.block.SlidingPanelsBlockEntity;
import com.doorways.block.WideDoorBlock;
import com.doorways.block.WideDoorGeometry;
import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.Swing;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.doorways.Doorways;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

    /** How far in front of the panel's face a painting is drawn, in blocks. */
    private static final float DECAL_GAP = 0.002F;

    /** Where the two tracks sit across the block, in blocks. Mirrors the generated models. */
    private static final float NEAR_TRACK = 3.0F / 16.0F;
    private static final float FAR_TRACK = 6.0F / 16.0F;

    /** Resolves a painting's texture to its place in the block atlas. */
    private final SpriteGetter sprites;

    public SlidingPanelsRenderer(SpriteGetter sprites) {
        this.sprites = sprites;
    }

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
        state.painting = null;

        BlockState blockState = door.getBlockState();
        if (!(door.getLevel() instanceof ClientLevel level)
                || !(blockState.getBlock() instanceof WideDoorBlock block)) {
            return;
        }

        // The offset is a signed count of columns; the wall axis turns it into a direction.
        float offset = door.panelOffset(partialTicks);
        Direction wall = WideDoorGeometry.toMinecraft(block.layoutOf(blockState).wallAxis());
        state.xOffset = wall.getStepX() * offset;
        state.zOffset = wall.getStepZ() * offset;

        // Before the question of who draws the panel, because the answer differs: the mesh draws
        // a fusuma standing still, but it cannot draw the painting on it -- a painting is not in
        // any model. So the panel is this renderer's job only sometimes, and the painting always.
        extractPainting(door, state, block, blockState);

        if (!door.isDrawnHere()) {
            return;
        }

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
     * it was drawing a moment ago.
     *
     * <p>This call is deprecated under NeoForge, which would rather it went through its own model
     * extensions, and is not deprecated in the game itself -- so on the vanilla side a
     * {@code @SuppressWarnings("deprecation")} here is flagged as unnecessary. There is no
     * spelling that satisfies both: an extension method does not exist on Fabric, and `common`
     * compiles against each in turn. The NeoForge build therefore prints one note about a
     * deprecated API, and that note is this.
     */
    private static void collectParts(BlockState state, BlockPos pos,
                                     List<BlockStateModelPart> into) {
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
                .collectParts(RandomSource.create(state.getSeed(pos)), into);
    }

    /**
     * Works out which quarter of which painting this block shows.
     *
     * <p>The rotation is taken from the same {@code leafDirection} the blockstate generator uses,
     * because the panel itself is drawn from a model the blockstate has already turned, and a
     * painting that did not turn with it would end up on the wrong face of the door.
     */
    private void extractPainting(SlidingPanelsBlockEntity door, SlidingPanelsRenderState state,
                                 WideDoorBlock block, BlockState blockState) {
        DoorPattern pattern = door.pattern();
        if (pattern == null) {
            return;
        }
        // The mapper knows which atlas block textures live on and what they are called there,
        // which is one fact fewer to write down and get wrong.
        // One canvas per width: a painting covers the whole door, so a 4-wide door carries a
        // wider picture rather than the same picture stretched.
        int width = block.width();
        SpriteId id = Sheets.BLOCKS_MAPPER.apply(Identifier.fromNamespaceAndPath(
                Doorways.MOD_ID, "painting/" + pattern.id() + "_" + width));
        TextureAtlasSprite sprite = sprites.get(id);

        DoorLayout layout = block.layoutOf(blockState);
        int part = block.partOf(blockState);
        boolean upper = blockState.getValue(WideDoorBlock.HALF) == DoubleBlockHalf.UPPER;
        float slice = (float) part / width;
        float next = (float) (part + 1) / width;

        state.painting = sprite;
        state.paintingType = id.renderType(RenderTypes::entityCutout);
        state.paintingU0 = Mth.lerp(slice, sprite.getU0(), sprite.getU1());
        state.paintingU1 = Mth.lerp(next, sprite.getU0(), sprite.getU1());
        // The back of the door shows the picture the right way round too, and that means the
        // opposite slice read backwards -- not this slice read backwards. Reversing in place
        // left each panel readable and broke every join, because seen from behind the panels
        // are in the other order.
        state.paintingBackU0 = Mth.lerp(1.0F - slice, sprite.getU0(), sprite.getU1());
        state.paintingBackU1 = Mth.lerp(1.0F - next, sprite.getU0(), sprite.getU1());
        state.paintingV0 = Mth.lerp(upper ? 0.0F : 0.5F, sprite.getV0(), sprite.getV1());
        state.paintingV1 = Mth.lerp(upper ? 0.5F : 1.0F, sprite.getV0(), sprite.getV1());
        state.frontTrack = layout.parksHere(part);
        state.leafRotation = yRotation(
                WideDoorGeometry.leafDirection(layout, part, Swing.CLOSED));
    }

    /** The same mapping the blockstate generator uses: the base model faces east. */
    private static int yRotation(Direction leaf) {
        return switch (leaf) {
            case EAST -> 0;
            case SOUTH -> 90;
            case WEST -> 180;
            case NORTH -> 270;
            default -> 0;
        };
    }

    /**
     * Draws the painting on both faces of the panel, the far one mirrored so that it reads the
     * same way from either side of the door.
     */
    private static void submitPainting(SlidingPanelsRenderState state, PoseStack poseStack,
                                       SubmitNodeCollector collector) {
        if (state.painting == null) {
            return;
        }
        poseStack.pushPose();
        // The model the panel is drawn from was turned by its blockstate; this turns with it,
        // about the same point -- the centre of the block, not the hinge.
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.leafRotation));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        float near = (state.frontTrack ? 0.0F : NEAR_TRACK) - DECAL_GAP;
        float far = (state.frontTrack ? NEAR_TRACK : FAR_TRACK) + DECAL_GAP;
        float u0 = state.paintingU0;
        float u1 = state.paintingU1;
        float backU0 = state.paintingBackU0;
        float backU1 = state.paintingBackU1;
        float v0 = state.paintingV0;
        float v1 = state.paintingV1;
        int light = state.lightCoords;

        collector.submitCustomGeometry(poseStack, state.paintingType,
                (pose, buffer) -> {
                    face(pose, buffer, near, -1.0F, u0, u1, v0, v1, light);
                    face(pose, buffer, far, 1.0F, backU0, backU1, v0, v1, light);
                });
        poseStack.popPose();
    }

    /** One face of the painting: a flat quad across the whole block, at the given depth. */
    private static void face(PoseStack.Pose pose, VertexConsumer buffer, float x, float normal,
                             float u0, float u1, float v0, float v1, int light) {
        vertex(pose, buffer, x, 0.0F, 0.0F, u0, v1, normal, light);
        vertex(pose, buffer, x, 0.0F, 1.0F, u1, v1, normal, light);
        vertex(pose, buffer, x, 1.0F, 1.0F, u1, v0, normal, light);
        vertex(pose, buffer, x, 1.0F, 0.0F, u0, v0, normal, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y,
                               float z, float u, float v, float normal, int light) {
        buffer.addVertex(pose, x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal, 0.0F, 0.0F);
    }

    @Override
    public void submit(SlidingPanelsRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.panel == null && state.painting == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(state.xOffset, 0.0F, state.zOffset);
        if (state.panel != null) {
            collector.submitMovingBlock(poseStack, state.panel, 0);
        }
        // Inside the same translation, so the cracks travel with the panel rather than staying
        // behind on the block the panel came from.
        if (state.panel != null && state.breakProgress != null && !state.breakingParts.isEmpty()) {
            collector.submitBreakingBlockModel(
                    poseStack, state.breakingParts, state.breakProgress.progress());
        }
        // The painting travels with the panel and turns on its own, so it takes the same
        // translation and adds a rotation of its own.
        submitPainting(state, poseStack, collector);
        poseStack.popPose();
    }
}
