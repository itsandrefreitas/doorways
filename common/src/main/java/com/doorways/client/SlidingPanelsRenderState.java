package com.doorways.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
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
}
