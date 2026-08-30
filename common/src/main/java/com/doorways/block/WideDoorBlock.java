package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.DoorMode;
import com.doorways.core.geometry.Hinge;
import com.doorways.core.geometry.Swing;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * An articulated door 1 to 4 blocks wide and 2 tall.
 *
 * <p>A single class covers all four widths and both modes, as §10 of the specification requires
 * ("generalise without copying the same logic four times"). All the geometry comes from
 * {@link DoorLayout}, which is pure and tested without the game.
 *
 * <p>The structure uses no block entity: any part reconstructs the origin from its own
 * {@code PART}, {@code FACING}, {@code HINGE} and {@code SWING} (§3).
 */
public class WideDoorBlock extends Block {

    public static final MapCodec<WideDoorBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(
                Codec.intRange(DoorLayout.MIN_WIDTH, DoorLayout.MAX_WIDTH)
                        .fieldOf("width")
                        .forGetter(b -> b.width),
                Codec.STRING
                        .xmap(DoorMode::valueOf, DoorMode::name)
                        .fieldOf("mode")
                        .forGetter(b -> b.mode),
                Codec.STRING
                        .xmap(DoorStyle::valueOf, DoorStyle::name)
                        .fieldOf("style")
                        .forGetter(b -> b.style),
                BlockSetType.CODEC.fieldOf("block_set_type").forGetter(b -> b.type),
                propertiesCodec())
            .apply(i, WideDoorBlock::new));

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;

    /**
     * Where the leaf sits: in its frame, or swung to one side of it.
     *
     * <p>Three values rather than vanilla's boolean {@code open}, because a door on a spring
     * hinge can be open on either side and every part has to know which. With no block entity,
     * a column locates its siblings by subtracting its own offset from its own position (§3) --
     * and that offset depends on the direction it swung. A part that could not tell the two
     * apart would not find the rest of its door.
     *
     * <p>It costs the fifth and last slot a {@code PropertyDispatch} can hold, which is why
     * {@code POWERED} could never have joined it in the blockstate files (D-24).
     */
    public static final EnumProperty<DoorSwing> SWING =
            EnumProperty.create("swing", DoorSwing.class);

    /**
     * Whether any part of the structure is receiving a redstone signal (D-24).
     *
     * <p>It exists to detect signal <b>edges</b>: without it, a door opened by hand would close
     * on the first neighbour update to arrive. It appears in no blockstate JSON -- the keys omit
     * it, so each variant serves both values, exactly as vanilla does.
     */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /**
     * The column's horizontal index. Fixed at 0..3 for every width:
     * {@code createBlockStateDefinition} runs from {@link Block}'s constructor, before the
     * subclass fields exist, so the range cannot depend on the instance (DECISIONS.md, D-22).
     */
    public static final IntegerProperty PART =
            IntegerProperty.create("part", 0, DoorLayout.MAX_WIDTH - 1);

    /** Leaf thickness, the same as a vanilla door: 3/16 of a block, flush against one face. */
    private static final Map<Direction, VoxelShape> LEAF_SHAPES =
            Shapes.rotateHorizontal(Block.boxZ(16.0, 13.0, 16.0));

    /**
     * The same leaf, hung down the middle of its block instead of against one face.
     *
     * <p>Used by spring doors, and only while they are <b>closed</b>. A saloon door hangs in the
     * middle of its frame rather than against one side of it, which is what the shape looks
     * like; but the moment it swings, it has to go back to the flush box every other door uses.
     * A blockstate turns a model about the centre of its block, not about the hinge, so a
     * centred box rotated 90° stays centred -- a bar across the middle of the doorway, at right
     * angles to it and attached to nothing.
     *
     * <p>The price is that the pivot appears to shift by a pixel and a half between the two
     * states, which nobody can see. These numbers are duplicated in {@code tools/gen_assets.py},
     * which builds the model this collision has to agree with.
     */
    private static final Map<Direction, VoxelShape> CENTRED_LEAF_SHAPES =
            Shapes.rotateHorizontal(Block.boxZ(16.0, 6.5, 9.5));

    /**
     * The transaction guard (D-08). While it is active the parts ignore the integrity logic:
     * midway through an open or close the structure is temporarily inconsistent, and neighbour
     * updates would otherwise destroy the door during the operation itself.
     *
     * <p>Per-thread rather than global: a dedicated server has only the server thread, but in
     * singleplayer the client and the integrated server run on different threads over the same
     * world. A shared static flag would let one side clear the guard while the other was
     * mid-transaction, firing the integrity guards at the exact moment the structure is
     * inconsistent on purpose.
     */
    private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> false);

    private static boolean inTransaction() {
        return IN_TRANSACTION.get();
    }

    private static void inTransaction(boolean value) {
        IN_TRANSACTION.set(value);
    }

    private final int width;
    private final DoorMode mode;
    private final DoorStyle style;
    private final BlockSetType type;

    public WideDoorBlock(int width, DoorMode mode, DoorStyle style, BlockSetType type,
                         BlockBehaviour.Properties properties) {
        // The sound is chosen by DoorVariant: BlockSetType still supplies the open and
        // close sounds, but step, break and place follow the material.
        super(properties);
        this.width = width;
        this.mode = mode;
        this.style = style;
        this.type = type;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(HINGE, DoorHingeSide.LEFT)
                .setValue(SWING, DoorSwing.CLOSED)
                .setValue(POWERED, false)
                .setValue(PART, 0));
    }

    @Override
    protected MapCodec<? extends WideDoorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, HINGE, SWING, POWERED, PART);
    }

    public int width() {
        return width;
    }

    public DoorMode mode() {
        return mode;
    }

    public DoorStyle style() {
        return style;
    }

    public BlockSetType type() {
        return type;
    }

    /** Where this part's leaf currently sits, in the terms {@code core} uses. */
    public static Swing swingOf(BlockState state) {
        return WideDoorGeometry.toCore(state.getValue(SWING));
    }

    /**
     * The column index, clamped to this block's real width.
     *
     * <p>{@code PART} goes up to 3 on every block (D-22), so narrow blocks have states that
     * never exist in the world -- but which Minecraft <b>evaluates anyway</b> when precomputing
     * shapes and collisions at registration. Without this clamp, a width-1 door blows up at
     * start-up when asked about {@code PART = 1}.
     */
    private int partOf(BlockState state) {
        return Math.min(state.getValue(PART), width - 1);
    }

    /**
     * The position of this part's <b>lower</b> half.
     *
     * <p>The geometry is entirely horizontal and assumes the origin sits at the bottom level.
     * Without this normalisation, interacting with the upper half yields an origin one block
     * too high: the door is rebuilt at {@code y+1}, the original lower half is orphaned, and
     * the door appears to grow upwards.
     */
    private static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /** The geometric layout matching a state. */
    public DoorLayout layoutOf(BlockState state) {
        return new DoorLayout(
                WideDoorGeometry.toCore(state.getValue(FACING)),
                width,
                mode,
                state.getValue(HINGE) == DoorHingeSide.LEFT ? Hinge.LEFT : Hinge.RIGHT);
    }

    // ------------------------------------------------------------------ shape

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        Swing swing = swingOf(state);
        Direction leaf = WideDoorGeometry.leafDirection(layoutOf(state), partOf(state), swing);
        boolean centred = style.springLoaded() && swing == Swing.CLOSED;
        return (centred ? CENTRED_LEAF_SHAPES : LEAF_SHAPES).get(leaf);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return switch (type) {
            case LAND, AIR -> swingOf(state).isOpen();
            case WATER -> false;
        };
    }

    // -------------------------------------------------------------- placement

    /**
     * The clicked position takes {@code PART 0}; the door extends to the player's right, along
     * the wall axis. Returns {@code null} -- cancelling placement -- if any of the
     * {@code width × 2} positions is unavailable (§4, atomic).
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        if (clicked.getY() >= level.getMaxY()) {
            return null;
        }

        DoorHingeSide aim = hingeFor(context);

        // Each alignment is tried and the first that fits wins: first the one that **centres**
        // the door on the click, then the two ends, then the rest. That way a 3-wide door
        // clicked in the middle grows one column each way, and against a pillar it slides by
        // itself to whichever side has room instead of refusing.
        //
        // The hinge plays no part in this choice: the closed footprint is a line along the wall
        // and does not depend on the rotation axis.
        for (int clickedPart : alignmentPreference()) {
            BlockState state = defaultBlockState()
                    .setValue(FACING, context.getHorizontalDirection())
                    .setValue(HALF, DoubleBlockHalf.LOWER)
                    .setValue(PART, clickedPart);

            DoorLayout layout = layoutOf(state);
            BlockPos origin = WideDoorGeometry.origin(clicked, layout, clickedPart, Swing.CLOSED);
            List<BlockPos> columns = WideDoorGeometry.columns(origin, layout, Swing.CLOSED);
            if (!fits(level, context, columns)) {
                continue;
            }
            // The door is always placed closed. Setting SWING here would make the state claim
            // "open" while the blocks still sit on the closed footprint, and the next operation
            // would clear the open positions -- which belong to whatever else is there.
            //
            // A spring door never records a signal: it cannot be held open, so POWERED would be
            // a value nobody ever reads.
            return state.setValue(HINGE, pivotFor(clickedPart, aim))
                    .setValue(SWING, DoorSwing.CLOSED)
                    .setValue(POWERED, !style.springLoaded() && hasSignal(level, columns));
        }
        return null;
    }

    /**
     * The column that becomes the pivot, given the clicked column.
     *
     * <p>Clicking an end puts the pivot at that end, so the door opens towards the clicked
     * block. Only when the click lands on a middle column does the aim within the block decide,
     * by the vanilla rule.
     *
     * <p>On a width-1 door both ends are the same column, so the aim always decides.
     */
    private DoorHingeSide pivotFor(int clickedPart, DoorHingeSide aim) {
        if (width == 1) {
            return aim;
        }
        if (clickedPart == 0) {
            return DoorHingeSide.LEFT;
        }
        if (clickedPart == width - 1) {
            return DoorHingeSide.RIGHT;
        }
        return aim;
    }

    /** The order alignments are tried in: centred, then the ends, then the rest. */
    private int[] alignmentPreference() {
        int[] order = new int[width];
        boolean[] used = new boolean[width];
        int n = 0;
        for (int candidate : new int[] {(width - 1) / 2, 0, width - 1}) {
            if (!used[candidate]) {
                used[candidate] = true;
                order[n++] = candidate;
            }
        }
        for (int i = 0; i < width; i++) {
            if (!used[i]) {
                order[n++] = i;
            }
        }
        return order;
    }

    private boolean fits(Level level, BlockPlaceContext context, List<BlockPos> columns) {
        for (BlockPos column : columns) {
            if (!canOccupy(level, column, context) || !canOccupy(level, column.above(), context)) {
                return false;
            }
            BlockPos below = column.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Which side the hinge goes on, from where the player clicked within the block.
     *
     * <p>This is the half of the vanilla rule that gives the player control: clicking the left
     * half of the face hinges left, the right half hinges right. The other half of the vanilla
     * rule -- biasing the decision because of solid blocks or neighbouring doors -- does not
     * carry over to a door that already occupies several columns, and is left out.
     */
    private static DoorHingeSide hingeFor(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        BlockPos pos = context.getClickedPos();
        Vec3 click = context.getClickLocation();
        double x = click.x - pos.getX();
        double z = click.z - pos.getZ();
        int stepX = facing.getStepX();
        int stepZ = facing.getStepZ();
        boolean left = (stepX >= 0 || z >= 0.5)
                && (stepX <= 0 || z <= 0.5)
                && (stepZ >= 0 || x <= 0.5)
                && (stepZ <= 0 || x >= 0.5);
        return left ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
    }

    private static boolean canOccupy(Level level, BlockPos pos, BlockPlaceContext context) {
        BlockState existing = level.getBlockState(pos);
        return existing.canBeReplaced(context) && existing.getFluidState().isEmpty();
    }

    /**
     * Places the remaining {@code width × 2 - 1} parts.
     *
     * <p>{@code placed} is the clicked column, which the game has already placed. It can be any
     * {@code PART}, not necessarily 0 -- it depends on the hinge (see
     * {@link #getStateForPlacement}) -- so the origin is reconstructed from it.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos placed, BlockState state,
                            @Nullable LivingEntity by, ItemStack stack) {
        DoorLayout layout = layoutOf(state);
        BlockPos origin = WideDoorGeometry.origin(placed, layout, partOf(state), Swing.CLOSED);
        List<BlockPos> columns = WideDoorGeometry.columns(origin, layout, Swing.CLOSED);
        inTransaction(true);
        try {
            for (int part = 0; part < layout.width(); part++) {
                BlockPos column = columns.get(part);
                BlockState lower = state.setValue(PART, part).setValue(HALF, DoubleBlockHalf.LOWER);
                if (!column.equals(placed)) {
                    level.setBlock(column, lower, Block.UPDATE_ALL);
                }
                level.setBlock(column.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER),
                        Block.UPDATE_ALL);
            }
        } finally {
            inTransaction(false);
        }

        // Placed next to an already-active signal: it opens next, through the normal path.
        // Opening here would mean duplicating the displacement logic, and marking it open at
        // placement would leave the state disagreeing with where the blocks actually are.
        if (state.getValue(POWERED)) {
            BlockState placedState = level.getBlockState(placed);
            if (placedState.is(this)) {
                apply(level, placedState, placed, Swing.OUT, true, false);
            }
        }
    }

    /**
     * Signal polling while the door is open by redstone <b>and</b> displaced.
     *
     * <p>A door that leaves its frame no longer has blocks touching the signal source, so it
     * never receives the neighbour update announcing the signal dropping -- and it stayed open
     * forever. Only the widths that move blocks need this; 1 and 2 still close through the
     * normal path, at no cost.
     */
    private static final int SIGNAL_POLL_TICKS = 5;

    /**
     * How long a spring-hinged door stays open: 40 ticks, two seconds.
     *
     * <p>Long enough to walk through a 4-wide door without it shutting on your back, short
     * enough that the spring is what you notice rather than a delay.
     */
    private static final int SPRING_CLOSE_TICKS = 40;

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!swingOf(state).isOpen()) {
            return;
        }
        if (style.springLoaded()) {
            springBack(level, state, pos);
            return;
        }
        onSignalChanged(level, state, pos);

        // Keep polling while it stays open. If it closed, it is back in its frame and the
        // normal neighbour updates reach it again.
        BlockState now = level.getBlockState(pos);
        if (now.is(this) && swingOf(now).isOpen()) {
            level.scheduleTick(pos, this, SIGNAL_POLL_TICKS);
        }
    }

    /**
     * The spring pulling the leaf back into its frame.
     *
     * <p>A blocked close does not give up. Whoever is standing in the doorway is holding the
     * leaf where it is, and the spring keeps pressing until they move -- which is what a spring
     * does, and the only behaviour that cannot leave a door stuck open with nothing on screen to
     * explain why.
     *
     * <p>Silent while blocked, deliberately: the locked sound every two seconds for as long as
     * someone stands in a doorway would be unbearable.
     */
    private void springBack(ServerLevel level, BlockState state, BlockPos pos) {
        if (!apply(level, state, pos, Swing.CLOSED, false, false)) {
            level.scheduleTick(pos, this, SPRING_CLOSE_TICKS);
        }
    }

    /**
     * Reacts to a signal edge. One path shared by the neighbour update and by the polling.
     */
    private void onSignalChanged(Level level, BlockState state, BlockPos pos) {
        Swing swing = swingOf(state);
        DoorLayout layout = layoutOf(state);
        BlockPos origin = WideDoorGeometry.origin(lowerHalf(state, pos), layout, partOf(state), swing);
        boolean signal = hasSignal(level, WideDoorGeometry.columns(origin, layout, Swing.CLOSED));

        if (signal == state.getValue(POWERED)) {
            return;
        }
        // Redstone only ever swings a door the way it was always going to swing: there is no
        // player to push it, so there is no side to push it from.
        Swing target = signal ? Swing.OUT : Swing.CLOSED;
        if (target != swing && apply(level, state, pos, target, signal, true)) {
            return;
        }
        // Either it was already in the right state, or the leaf is obstructed. The new POWERED
        // is stored anyway to consume the edge -- otherwise every neighbour update would retry
        // opening and replay the blocked sound.
        apply(level, state, pos, swing, signal, false);
    }

    /**
     * Redstone (D-24). The door opens and closes with the signal, reacting only to <b>edges</b>:
     * while the signal does not change, a door opened by hand stays as it is.
     *
     * <p>Spring-hinged styles opt out entirely. A spring has no latch, so a signal holding the
     * door open and a spring pulling it shut would only fight each other -- see
     * {@link DoorStyle#springLoaded()}.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                   @Nullable Orientation orientation, boolean movedByPiston) {
        if (level.isClientSide() || inTransaction() || style.springLoaded()) {
            return;
        }
        onSignalChanged(level, state, pos);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (inTransaction()) {
            return true;
        }
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            return level.getBlockState(pos.below()).is(this);
        }
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    // ------------------------------------------------------------ interaction

    /**
     * A copper conversion on one column propagates to the <b>whole</b> door.
     *
     * <p>{@code AxeItem} and {@code HoneycombItem} swap a single block, which on a wide door
     * would leave one column scraped and the others oxidised. Intercepting both items in
     * {@code useItemOn} does not work, for two reasons:
     *
     * <ul>
     *   <li>A block's {@code useItemOn} is <b>skipped</b> while the player is crouching, which
     *       is how copper is scraped in vanilla. Only the non-crouching case would be caught.
     *   <li>It requires reimplementing the wax lookups, and those differ per loader.
     *       {@code HoneycombItem.WAXABLES} is a static field NeoForge does not populate; it
     *       uses data maps and patches the callers instead.
     * </ul>
     *
     * <p>Reacting in {@code onPlace} instead keeps vanilla behaviour intact: with an axe in
     * hand the door opens, crouching scrapes, and sound, particles, tool damage and honeycomb
     * consumption all come from the item. See DECISIONS.md, D-31.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level.isClientSide() || inTransaction() || !isConversion(state, oldState)) {
            return;
        }
        convertStructure(level, state, pos, this);
    }

    /**
     * Tells a conversion apart from everything else: a door of the same shape, a different
     * block, and all the rest of the state identical.
     *
     * <p>That is exactly what {@code withPropertiesOf} produces when swapping one copper block
     * for another. Placing, opening and closing never produce this -- either the previous block
     * is not a door, or it is this very block.
     */
    private boolean isConversion(BlockState state, BlockState oldState) {
        return oldState.getBlock() instanceof WideDoorBlock old
                && old != this
                && old.width == width
                && old.mode == mode
                && old.style == style
                && oldState.getValue(FACING) == state.getValue(FACING)
                && oldState.getValue(HINGE) == state.getValue(HINGE)
                && oldState.getValue(HALF) == state.getValue(HALF)
                && oldState.getValue(SWING) == state.getValue(SWING)
                && oldState.getValue(PART).equals(state.getValue(PART));
    }

    /** Interacting with any part moves the whole door (§5). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!type.canOpenByHand()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        boolean moved = apply(level, state, pos, nextSwing(state, pos, player),
                state.getValue(POWERED), true);
        return moved ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    /**
     * Where the leaf goes when a player interacts with it.
     *
     * <p>An ordinary door only has one open position, so interacting toggles. A spring door is
     * <b>pushed</b> rather than opened: it goes away from whoever touched it, which is decided
     * by which side of the wall line the player is standing on.
     *
     * <p>If that side turns out to be obstructed the door refuses, rather than swinging the
     * other way. Opening towards someone because the far side happened to be blocked is not
     * something a player can predict, and a door that sometimes comes at you is worse than a
     * door that sometimes will not budge.
     */
    private Swing nextSwing(BlockState state, BlockPos pos, Player player) {
        if (swingOf(state).isOpen()) {
            return Swing.CLOSED;
        }
        if (!style.springLoaded()) {
            return Swing.OUT;
        }
        Direction facing = state.getValue(FACING);
        Vec3 fromDoor = player.position().subtract(Vec3.atCenterOf(lowerHalf(state, pos)));
        double side = fromDoor.x * facing.getStepX() + fromDoor.z * facing.getStepZ();

        // FACING points away from whoever placed the door (D-04), so a player standing on the
        // +FACING side is in front of the leaf and pushes it back the other way.
        return side > 0 ? Swing.BACK : Swing.OUT;
    }

    /**
     * Rebuilds the whole structure in the requested state. One path shared by hand and by
     * redstone.
     *
     * <p>Returns {@code false} -- and plays the blocked sound -- if the transition requires
     * occupied positions (§5.1, §6). When only {@code POWERED} changes nothing is displaced, so
     * there is nothing that could block.
     */
    private boolean apply(Level level, BlockState state, BlockPos pos,
                          Swing targetSwing, boolean targetPowered, boolean audible) {
        DoorLayout layout = layoutOf(state);
        Swing swing = swingOf(state);
        BlockPos origin = WideDoorGeometry.origin(lowerHalf(state, pos), layout, partOf(state), swing);
        boolean moves = targetSwing != swing;

        if (moves) {
            for (BlockPos column :
                    WideDoorGeometry.newlyOccupied(origin, layout, swing, targetSwing)) {
                if (!isFree(level, column) || !isFree(level, column.above())) {
                    if (audible) {
                        level.playSound(null, pos, SoundEvents.CHEST_LOCKED,
                                SoundSource.BLOCKS, 0.6F, 1.0F);
                    }
                    return false;
                }
            }
        }

        List<BlockPos> from = WideDoorGeometry.columns(origin, layout, swing);
        List<BlockPos> to = WideDoorGeometry.columns(origin, layout, targetSwing);

        // Grass, flowers and carpets in the leaf's path are broken properly, with a drop,
        // instead of vanishing silently when setBlock runs over them. Outside the transaction:
        // these drops are legitimate, unlike those of the door's own parts.
        if (moves) {
            for (BlockPos column :
                    WideDoorGeometry.newlyOccupied(origin, layout, swing, targetSwing)) {
                breakLoose(level, column);
                breakLoose(level, column.above());
            }
        }

        inTransaction(true);
        try {
            // Only demolish when the leaf actually changes position. On a POWERED-only change
            // the positions are identical, so clearing and rewriting would be wasted work.
            if (moves) {
                for (BlockPos column : from) {
                    clear(level, column);
                    clear(level, column.above());
                }
            }
            for (int part = 0; part < layout.width(); part++) {
                BlockState lower = state.setValue(PART, part)
                        .setValue(SWING, WideDoorGeometry.toMinecraft(targetSwing))
                        .setValue(POWERED, targetPowered)
                        .setValue(HALF, DoubleBlockHalf.LOWER);
                level.setBlock(to.get(part), lower, Block.UPDATE_CLIENTS);
                level.setBlock(to.get(part).above(), lower.setValue(HALF, DoubleBlockHalf.UPPER),
                        Block.UPDATE_CLIENTS);
            }
        } finally {
            inTransaction(false);
        }

        if (!moves) {
            // Nothing moved: no neighbour cares about this, and firing the flush would only
            // feed update chains.
            return true;
        }

        // A single flush at the end, once the structure is consistent again (D-08).
        for (BlockPos column : to) {
            level.updateNeighborsAt(column, this);
            level.updateNeighborsAt(column.above(), this);
        }

        // Two reasons to come back later, and a door only ever needs one of them.
        //
        // A spring door is due to close. Every other door has left its frame, so the signal
        // source no longer touches it and no signal change reaches it by neighbour update --
        // it has to poll. Polling starts regardless of what opened it: a door opened by hand
        // must still react to a plate afterwards.
        if (targetSwing.isOpen()) {
            level.scheduleTick(to.get(0), this,
                    style.springLoaded() ? SPRING_CLOSE_TICKS : SIGNAL_POLL_TICKS);
        }

        // A null entity on purpose: on the server, passing the player *excludes* them from
        // receiving the sound. Vanilla can pass it because useWithoutItem also runs client-side
        // and plays the sound locally; this block returns early on the client, so passing the
        // player would leave the sound inaudible to them.
        level.playSound(null, pos, targetSwing.isOpen() ? type.doorOpen() : type.doorClose(),
                SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        return true;
    }

    /**
     * Whether the leaf may occupy this position.
     *
     * <p>Requiring air is not enough: tall grass, flowers and snow layers are replaceable and
     * must not stop the door opening. Air alone would make any wide door placed in a grassy
     * field impossible to open, since placement accepts that terrain.
     */
    private boolean isFree(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(this)) {
            return true;
        }
        // Machinery is never broken. Pressure plates, levers, buttons, dust and repeaters are
        // all PushReaction.DESTROY, so the rule below would otherwise let the leaf destroy the
        // very plate that opened the door. They block the swing like a solid block.
        if (state.isSignalSource()) {
            return false;
        }
        return state.getPistonPushReaction() == PushReaction.DESTROY
                && state.getFluidState().isEmpty();
    }

    /**
     * A redstone signal on any column of the structure.
     *
     * <p>The door is one unit: one column receiving a signal is enough for the whole door to
     * react. Without this, a pressure plate in front of a 4-wide door would only work if it sat
     * against the right column.
     *
     * <p><b>The columns passed in must always be the closed footprint</b>, even with the door
     * open: the frame is the reference and the frame does not move. Reading the signal at the
     * <i>current</i> positions oscillates endlessly -- on opening, the leaf moves away from the
     * plate, the signal drops, the door closes, the plate touches it again, and so on until the
     * server aborts the update chain.
     */
    private static boolean hasSignal(Level level, List<BlockPos> columns) {
        for (BlockPos column : columns) {
            if (level.hasNeighborSignal(column) || level.hasNeighborSignal(column.above())) {
                return true;
            }
        }
        return false;
    }

    /** Every position the structure occupies, in both halves. */
    protected List<BlockPos> structurePositions(BlockState state, BlockPos pos) {
        DoorLayout layout = layoutOf(state);
        Swing swing = swingOf(state);
        BlockPos origin = WideDoorGeometry.origin(lowerHalf(state, pos), layout, partOf(state), swing);
        List<BlockPos> all = new ArrayList<>();
        for (BlockPos column : WideDoorGeometry.columns(origin, layout, swing)) {
            all.add(column);
            all.add(column.above());
        }
        return all;
    }

    /**
     * Swaps the whole structure for another block, keeping orientation, hinge and state.
     *
     * <p>This is what makes a wide door oxidise as <b>one</b> door rather than a cluster of
     * columns. Vanilla's {@code changeOverTime} swaps only the position it runs on, which would
     * leave a single column a different colour from the rest.
     */
    protected void convertStructure(Level level, BlockState state, BlockPos pos, Block target) {
        DoorLayout layout = layoutOf(state);
        Swing swing = swingOf(state);
        BlockPos origin = WideDoorGeometry.origin(lowerHalf(state, pos), layout, partOf(state), swing);
        List<BlockPos> columns = WideDoorGeometry.columns(origin, layout, swing);

        inTransaction(true);
        try {
            for (int part = 0; part < layout.width(); part++) {
                BlockState lower = target.defaultBlockState()
                        .setValue(FACING, state.getValue(FACING))
                        .setValue(HINGE, state.getValue(HINGE))
                        .setValue(SWING, state.getValue(SWING))
                        .setValue(POWERED, state.getValue(POWERED))
                        .setValue(PART, part)
                        .setValue(HALF, DoubleBlockHalf.LOWER);
                level.setBlock(columns.get(part), lower, Block.UPDATE_CLIENTS);
                level.setBlock(columns.get(part).above(),
                        lower.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
            }
        } finally {
            inTransaction(false);
        }
    }

    /** Breaks whatever is at this position, dropping its item. Does nothing for air. */
    private static void breakLoose(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            Block.dropResources(state, level, pos);
        }
    }

    /**
     * Removes one part of the door, with no drop.
     *
     * <p>Only blocks belonging to <b>this</b> door are touched. The ownership check is required:
     * should the state ever disagree with where the blocks actually are, clearing unchecked
     * would destroy whatever else occupies those positions.
     */
    private void clear(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).is(this)) {
            return;
        }
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
    }

    // --------------------------------------------------------------- breaking

    /**
     * Breaking any part removes the whole structure (§7). The clicked part is not removed here:
     * the game removes it next through the normal path, and that is where the single drop comes
     * from.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !inTransaction()) {
            removeRest(level, pos, state, !player.isCreative());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * The structure's anchor: the lower half of column {@code PART 0}.
     *
     * <p>It is the only part whose loot table drops anything -- the others fail the
     * {@code half=lower, part=0} condition. It is the same trick vanilla {@code oak_door} uses
     * to guarantee a single drop from a 2-block door, generalised across width.
     */
    private void removeRest(Level level, BlockPos pos, BlockState state, boolean dropAnchor) {
        DoorLayout layout = layoutOf(state);
        Swing swing = swingOf(state);
        BlockPos origin = WideDoorGeometry.origin(lowerHalf(state, pos), layout, partOf(state), swing);
        List<BlockPos> columns = WideDoorGeometry.columns(origin, layout, swing);

        // The anchor -- the lower half of column PART 0 -- is the only part whose loot table
        // drops anything. When the player breaks any other part its loot is empty, so the item
        // has to be dropped explicitly here.
        BlockPos anchor = columns.get(0);
        if (dropAnchor && !anchor.equals(pos)) {
            BlockState anchorState = level.getBlockState(anchor);
            if (anchorState.is(this)) {
                Block.dropResources(anchorState, level, anchor);
            }
        }

        List<BlockPos> all = new ArrayList<>();
        for (BlockPos column : columns) {
            all.add(column);
            all.add(column.above());
        }

        inTransaction(true);
        try {
            for (BlockPos part : all) {
                if (!part.equals(pos) && level.getBlockState(part).is(this)) {
                    clear(level, part);
                }
            }
        } finally {
            inTransaction(false);
        }
    }

    // ------------------------------------------------------ rotation and mirror

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return mirror == Mirror.NONE
                ? state
                : rotate(state, mirror.getRotation(state.getValue(FACING))).cycle(HINGE);
    }
}
