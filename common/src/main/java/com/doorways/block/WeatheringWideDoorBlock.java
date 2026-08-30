package com.doorways.block;

import com.doorways.core.geometry.DoorMode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.ChangeOverTimeBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import com.doorways.core.geometry.DoorLayout;

/**
 * A copper door that oxidises over time.
 *
 * <p>Follows vanilla {@code WeatheringCopperDoorBlock}: only the lower half ticks the clock, so
 * that a 2-block-tall door does not get twice the chance of changing state.
 *
 * <p>On a wide door that is not enough -- a 4-wide door has four lower halves and would oxidise
 * four times faster than a vanilla door. So only column {@code PART 0} ticks: one door, one
 * clock, whatever the width.
 */
public class WeatheringWideDoorBlock extends WideDoorBlock implements WeatheringCopper {

    /** The same constant as vanilla ChangeOverTimeBlock: each block once per day. */
    private static final float ONCE_PER_DAY_CHANCE = 0.05688889F;

    public static final MapCodec<WeatheringWideDoorBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(
                Codec.intRange(DoorLayout.MIN_WIDTH, DoorLayout.MAX_WIDTH)
                        .fieldOf("width").forGetter(WideDoorBlock::width),
                Codec.STRING.xmap(DoorMode::valueOf, DoorMode::name)
                        .fieldOf("mode").forGetter(WideDoorBlock::mode),
                BlockSetType.CODEC.fieldOf("block_set_type").forGetter(WideDoorBlock::type),
                WeatherState.CODEC
                        .fieldOf("weathering_state").forGetter(WeatheringWideDoorBlock::getAge),
                propertiesCodec())
            .apply(i, WeatheringWideDoorBlock::new));

    private final WeatherState weatherState;

    public WeatheringWideDoorBlock(int width, DoorMode mode, BlockSetType type,
                                   WeatherState weatherState,
                                   BlockBehaviour.Properties properties) {
        super(width, mode, type, properties);
        this.weatherState = weatherState;
    }

    @Override
    protected MapCodec<? extends WideDoorBlock> codec() {
        return CODEC;
    }

    @Override
    public WeatherState getAge() {
        return weatherState;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && state.getValue(PART) == 0) {
            changeOverTime(state, level, pos, random);
        }
    }

    /**
     * Identical to vanilla in probability, different in what it applies: the <b>whole</b> door
     * changes state, rather than only the column the tick landed on.
     */
    @Override
    public void changeOverTime(BlockState state, ServerLevel level, BlockPos pos,
                               RandomSource random) {
        if (random.nextFloat() < ONCE_PER_DAY_CHANCE) {
            getNextState(state, level, pos, random)
                    .ifPresent(next -> convertStructure(level, state, pos, next.getBlock()));
        }
    }

    /**
     * A copy of the vanilla rule with one difference: the other columns of <b>this</b> door do
     * not count as neighbours.
     *
     * <p>Vanilla counts the oxidisable blocks around it and slows down the more there are of
     * the same age. Without this exclusion, a 4-wide door counted its own 7 parts and oxidised
     * about sixteen times slower than a vanilla door -- it stopped behaving like one door.
     */
    @Override
    public Optional<BlockState> getNextState(BlockState state, ServerLevel level, BlockPos pos,
                                             RandomSource random) {
        Set<BlockPos> own = new HashSet<>(structurePositions(state, pos));
        int ownAge = getAge().ordinal();
        int sameAge = 0;
        int older = 0;

        for (BlockPos neighbour : BlockPos.withinManhattan(pos, SCAN_DISTANCE, SCAN_DISTANCE,
                SCAN_DISTANCE)) {
            if (neighbour.distManhattan(pos) > SCAN_DISTANCE) {
                break;
            }
            if (neighbour.equals(pos) || own.contains(neighbour)) {
                continue;
            }
            if (level.getBlockState(neighbour).getBlock() instanceof ChangeOverTimeBlock<?> other) {
                Enum<?> age = other.getAge();
                if (getAge().getClass() == age.getClass()) {
                    if (age.ordinal() < ownAge) {
                        return Optional.empty();
                    }
                    if (age.ordinal() > ownAge) {
                        older++;
                    } else {
                        sameAge++;
                    }
                }
            }
        }

        float chance = (float) (older + 1) / (older + sameAge + 1);
        return random.nextFloat() < chance * chance * getChanceModifier()
                ? getNext(state)
                : Optional.empty();
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return WeatheringCopper.getNext(state.getBlock()).isPresent();
    }
}
