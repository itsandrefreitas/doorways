package com.doorways.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.jspecify.annotations.Nullable;

/**
 * What the renderer needs, pulled off the block entity once per frame.
 *
 * <p>26.2 splits rendering in two: {@code extractRenderState} reads the world, and
 * {@code submit} draws from what it read and touches nothing else. This class is the handover
 * between them, and it is where the interpolated position will live once there is one.
 */
public class SlidingPanelsRenderState extends BlockEntityRenderState {

    /** The panel to draw, with the lighting of the position it is drawn at. */
    public @Nullable MovingBlockRenderState panel;

    /** How far along the wall to draw it, already turned from columns into world axes. */
    public float xOffset;
    public float zOffset;

    /**
     * The panel's model, in pieces, for the cracks that spread over a block being mined.
     *
     * <p>Empty unless something is being mined here. The list is the block entity's own and is
     * refilled each frame rather than allocated, because it is refilled every frame a player
     * spends breaking the door.
     */
    public final List<BlockStateModelPart> breakingParts = new ArrayList<>();

    /**
     * The painting on this panel, already resolved to a sprite, or null for bare paper.
     *
     * <p>What follows is one quarter of it. A painting spans a leaf -- two panels wide and two
     * blocks tall -- so each of the four blocks draws a corner, and the four corners meet.
     */
    public @Nullable TextureAtlasSprite painting;

    /** How to draw it: cutout, on whichever atlas the painting turned out to live on. */
    public @Nullable RenderType paintingType;

    /** The slice of the painting this block shows, in atlas coordinates. */
    public float paintingU0;
    public float paintingU1;

    /** The same panel seen from behind the door: the opposite slice, read backwards. */
    public float paintingBackU0;
    public float paintingBackU1;
    public float paintingV0;
    public float paintingV1;

    /** Which of the two tracks this panel runs on, which is where the painted face is. */
    public boolean frontTrack;

    /** The rotation the blockstate applies to this leaf's model, which the painting must match. */
    public int leafRotation;
}
