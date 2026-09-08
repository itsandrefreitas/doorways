package com.doorways.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The item that places a door, whatever its height.
 *
 * <p>Vanilla's {@code DoubleHighBlockItem} clears the one block above the click before placing,
 * which is exactly as far as a two-block door reaches. A gate stands {@value
 * WideDoorBlock#MAX_HEIGHT} rows, and every row above the first needs the same treatment for the
 * same reason: the game itself only ever looks at the clicked position, so the grass, snow layer
 * or vine two blocks up would survive inside the finished door.
 *
 * <p>Clearing is safe here because it happens after the placement has already been accepted:
 * {@code getStateForPlacement} refuses unless every position in the column is replaceable.
 */
public class TallDoorBlockItem extends BlockItem {

    private final int height;

    public TallDoorBlockItem(WideDoorBlock door, Properties properties) {
        super(door, properties);
        this.height = door.height();
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        for (int row = 1; row < height; row++) {
            // The same flags vanilla uses for the half above a door.
            level.setBlock(pos.above(row), Blocks.AIR.defaultBlockState(), 27);
        }
        return super.placeBlock(context, state);
    }
}
