package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.DoorMode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A door whose panels run along the wall instead of turning.
 *
 * <p>It exists as its own class for one property. {@link #SLIDING} is meaningless on a door that
 * swings -- there is nowhere for it to travel to -- but a property on the shared class is a
 * property on every door, and this one doubled the states of all 226 of them to serve the 26
 * that need it. Splitting the class cuts the mod's blockstates by 45%, from 173,568 to 96,768.
 *
 * <p>The boundary was already there in everything but the code: a sliding door has its own
 * motion, its own models, its own block entity and its own renderer. This only writes it down.
 */
public class SlidingDoorBlock extends WideDoorBlock {

    public static final MapCodec<SlidingDoorBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(
                Codec.intRange(DoorLayout.MIN_WIDTH, DoorLayout.MAX_WIDTH)
                        .fieldOf("width").forGetter(WideDoorBlock::width),
                Codec.STRING.xmap(DoorMode::valueOf, DoorMode::name)
                        .fieldOf("mode").forGetter(WideDoorBlock::mode),
                Codec.STRING.xmap(DoorStyle::valueOf, DoorStyle::name)
                        .fieldOf("style").forGetter(WideDoorBlock::style),
                BlockSetType.CODEC.fieldOf("block_set_type").forGetter(WideDoorBlock::type),
                propertiesCodec())
            .apply(i, (width, mode, style, type, properties) ->
                    sized(width, mode, () ->
                            new SlidingDoorBlock(width, mode, style, type, properties))));

    /**
     * Set while a panel is travelling, and only then.
     *
     * <p>A door at rest is an ordinary block that draws itself from its model, batched into the
     * chunk mesh like anything else. Only for the third of a second a panel is in flight does it
     * go invisible and hand its drawing to a renderer -- the same shape as vanilla's
     * {@code MOVING_PISTON}, a block that exists only while something is on the move.
     *
     * <p>Drawing every sliding door through the renderer instead worked, and was simpler, and
     * cost something that only showed in play: a block entity renderer reaches 64 blocks, and
     * past that the doors vanished while the wall around them stayed.
     *
     * <p>Like {@code POWERED}, it appears in no blockstate file (D-24). The model it would pick
     * is never drawn, so there is nothing for the keys to say.
     */
    public static final BooleanProperty SLIDING = BooleanProperty.create("sliding");

    public SlidingDoorBlock(int width, DoorMode mode, DoorStyle style, BlockSetType type,
                            BlockBehaviour.Properties properties) {
        super(width, mode, style, type, properties);
        registerDefaultState(defaultBlockState().setValue(SLIDING, false));
    }

    @Override
    protected MapCodec<? extends WideDoorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SLIDING);
    }

    @Override
    public boolean isMoving(BlockState state) {
        return state.getValue(SLIDING);
    }

    @Override
    protected BlockState withMoving(BlockState state, boolean moving) {
        return state.setValue(SLIDING, moving);
    }
}
