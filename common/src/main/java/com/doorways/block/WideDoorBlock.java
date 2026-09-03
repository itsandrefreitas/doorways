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
import java.util.function.Supplier;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
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
public class WideDoorBlock extends Block implements EntityBlock {

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
            .apply(i, (width, mode, style, type, properties) ->
                    sized(width, mode, () ->
                            new WideDoorBlock(width, mode, style, type, properties))));

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;

    /**
     * Where the leaf sits: in its frame, or swung out of it.
     *
     * <p>Two values, because that is all a door on an ordinary hinge can be. A column locates
     * its siblings by subtracting its own offset from its own position (§3), and that offset
     * depends on where the leaf is -- so every part has to carry this, and no part may carry a
     * value it can never hold.
     *
     * <p>{@link SpringDoorBlock} declares the same property with a third value, {@code BACK}.
     * Declaring it here instead put a state on all 226 doors to serve the 24 that swing both
     * ways -- 29,184 blockstates for a value the other 202 could never reach.
     *
     * <p>Read it through {@link #swingOf}, which asks the block which of the two it declares.
     * It costs the fifth and last slot a {@code PropertyDispatch} can hold, which is why
     * {@code POWERED} could never have joined it in the blockstate files (D-24).
     */
    public static final EnumProperty<DoorSwing> SWING =
            EnumProperty.create("swing", DoorSwing.class, DoorSwing.CLOSED, DoorSwing.OUT);

    /**
     * Whether any part of the structure is receiving a redstone signal (D-24).
     *
     * <p>It exists to detect signal <b>edges</b>: without it, a door opened by hand would close
     * on the first neighbour update to arrive. It appears in no blockstate JSON -- the keys omit
     * it, so each variant serves both values, exactly as vanilla does.
     */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** How long a panel takes to travel. The renderer interpolates over exactly this. */
    public static final int SLIDE_TICKS = 6;

    /**
     * How long the flag is held, which is deliberately longer than the travel.
     *
     * <p>The client starts its animation when the block update reaches it, which is never quite
     * when the server sent it. Clearing the flag at the exact tick the travel ends would hand
     * the drawing back while the panel was still a few pixels short, and it would jump the rest
     * of the way. Two ticks of slack cost nothing and remove the jump.
     */
    private static final int SLIDE_HOLD_TICKS = SLIDE_TICKS + 2;

    /**
     * The column's horizontal index, one property per width.
     *
     * <p>Indexed by width, and null at width 1: a door with one column has no index to keep, and
     * {@code IntegerProperty} rejects a range of one value anyway.
     *
     * <p>This was a single 0..3 property on every door (D-22), because
     * {@code createBlockStateDefinition} runs from {@link Block}'s constructor, before any field
     * of ours exists. It cost 22,528 blockstates in columns that could never be occupied: a
     * width-1 door carried three unreachable states for every real one. See {@link #sized}.
     */
    private static final IntegerProperty[] PARTS = {
            null, null,
            IntegerProperty.create("part", 0, 1),
            IntegerProperty.create("part", 0, 2),
            IntegerProperty.create("part", 0, 3),
    };

    /** What a door needs to know about itself before its constructor has run. */
    private record Shape(int width, DoorMode mode) {}

    /**
     * The shape of the door being built, for as long as its constructor runs.
     *
     * <p>A handover, and not a pretty one -- but the alternative is worse. The state definition
     * decides which properties a door declares, and is asked for it before the block can hold a
     * width or a mode, so the only other route is a class per shape <b>per family</b>: twelve
     * today, and four more for every kind of door added after.
     *
     * <p>It is safe where it is used and loud where it is not. Registration runs on one thread,
     * {@link #sized} sets and clears it around a single synchronous construction, and
     * {@code createBlockStateDefinition} throws outright if asked without one. A door built by
     * any other path fails at once instead of quietly taking the wrong columns.
     */
    private static final ThreadLocal<Shape> BUILDING = new ThreadLocal<>();

    /**
     * Builds a door, telling the class its shape before the constructor is asked for it.
     *
     * <p>Every door comes through here. Nothing else may call {@code new} on this class or any
     * of its subclasses.
     */
    public static <T extends Block> T sized(int width, DoorMode mode, Supplier<T> factory) {
        BUILDING.set(new Shape(width, mode));
        try {
            return factory.get();
        } finally {
            BUILDING.remove();
        }
    }

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
     * The far track of a sliding door, three pixels behind {@link #LEAF_SHAPES}.
     *
     * <p>A sliding leaf is two panels on two tracks. The panel that stays put runs on the near
     * track; the one that hides behind it runs on this one. The two are visibly offset even with
     * the door shut, which is what tells you at a glance that it slides rather than swings.
     */
    private static final Map<Direction, VoxelShape> BACK_TRACK_SHAPES =
            Shapes.rotateHorizontal(Block.boxZ(16.0, 10.0, 13.0));

    /** Both tracks at once: what the column a leaf parks in is filled with. */
    private static final Map<Direction, VoxelShape> STACKED_SHAPES =
            Shapes.rotateHorizontal(Block.boxZ(16.0, 10.0, 16.0));

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
        BlockState base = stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(swingProperty(), DoorSwing.CLOSED);
        registerDefaultState(
                withPart(withPowered(withHinge(base, DoorHingeSide.LEFT), false), 0));
    }

    @Override
    protected MapCodec<? extends WideDoorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        Shape building = BUILDING.get();
        if (building == null) {
            throw new IllegalStateException(
                    "a door must be built through WideDoorBlock.sized(width, mode, ...)");
        }
        builder.add(FACING, HALF, swingProperty());
        // Three properties every door has, and three it declares only if it reads them. A
        // property added here is added to all 226 doors, used or not (D-38).
        if (building.mode() != DoorMode.SPLIT) {
            builder.add(HINGE);
        }
        if (recordsSignal()) {
            builder.add(POWERED);
        }
        if (PARTS[building.width()] != null) {
            builder.add(PARTS[building.width()]);
        }
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

    /**
     * Whether a panel of this door is travelling, and its drawing has passed to a renderer.
     *
     * <p>Always false here. Only a door that slides has anywhere to travel <i>to</i>, so only
     * {@link SlidingDoorBlock} carries the property that answers this -- which is the whole
     * point: it multiplied the states of all 226 doors to serve the 26 that slide.
     */
    public boolean isMoving(BlockState state) {
        return false;
    }

    /** Records the answer to {@link #isMoving}. Leaves a door that cannot move untouched. */
    protected BlockState withMoving(BlockState state, boolean moving) {
        return state;
    }

    /**
     * The property that says where this door's leaf is.
     *
     * <p>A door that swings both ways declares a wider one. Everything reads it through here so
     * that the difference stays in the two classes that care.
     */
    public EnumProperty<DoorSwing> swingProperty() {
        return SWING;
    }

    /** Where this part's leaf currently sits, in the terms {@code core} uses. */
    public static Swing swingOf(BlockState state) {
        WideDoorBlock door = (WideDoorBlock) state.getBlock();
        return WideDoorGeometry.toCore(state.getValue(door.swingProperty()));
    }

    /**
     * A block entity, but only on the styles that slide, and only to draw them.
     *
     * <p>Nothing the game depends on lives in it -- see {@link SlidingPanelsBlockEntity}. Every
     * other style returns null and stays exactly as it was.
     */
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return style.slides() ? new SlidingPanelsBlockEntity(pos, state) : null;
    }

    /** How many ticks this panel still has to travel, or 0 if it is due. */
    private static int remainingSlide(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SlidingPanelsBlockEntity panels
                ? panels.remainingSlide(level.getGameTime())
                : 0;
    }

    /** Records a departure on the block entity that carries the arrival tick. */
    private static void departed(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SlidingPanelsBlockEntity panels) {
            panels.departed(level.getGameTime());
        }
    }

    /**
     * A sliding door is drawn entirely by its renderer, so the world draws nothing for it.
     *
     * <p>This is what buys the animation for free. A block's model comes from its state, and a
     * state is discrete -- there is no value between "shut" and "open" to hang a position on.
     * The alternative was to add the in-between positions as states, and since {@code SWING} is
     * shared by every door, that would have multiplied the variants of all 226 of them to give
     * two styles an animation. Handing the drawing to the renderer costs nothing anywhere else.
     *
     * <p>The price is that sliding doors are not batched into the chunk mesh, the same price
     * vanilla pays for chests, beds and signs.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return isMoving(state) || style.drawnByRenderer()
                ? RenderShape.INVISIBLE
                : super.getRenderShape(state);
    }

    /**
     * The column index, clamped to this block's real width.
     *
     * <p>Zero at width 1, where the property does not exist because there is nothing for it to
     * distinguish. Everywhere else its range is exactly this door's, so no state can name a
     * column the door does not have -- which is what the clamp that used to live here was for.
     */
    public int partOf(BlockState state) {
        IntegerProperty part = partProperty();
        return part == null ? 0 : state.getValue(part);
    }

    /** This door's column property, or null at width 1. */
    public @Nullable IntegerProperty partProperty() {
        return PARTS[width];
    }

    /**
     * This door's hinge property, or null on a door that opens from the middle.
     *
     * <p>A {@link DoorMode#SPLIT} door has no hinge to choose. Its two leaves each turn about
     * their own outer end -- {@code DoorLayout.pivotAtLowEnd} answers {@code part < width / 2}
     * and never looks at the hinge -- so the property was two values that changed nothing, on
     * 125 of the 226 doors (D-38).
     */
    public @Nullable EnumProperty<DoorHingeSide> hingeProperty() {
        return mode == DoorMode.SPLIT ? null : HINGE;
    }

    /** Which end this door hinges on. Always LEFT where there is no choice to make. */
    public DoorHingeSide hingeOf(BlockState state) {
        EnumProperty<DoorHingeSide> hinge = hingeProperty();
        return hinge == null ? DoorHingeSide.LEFT : state.getValue(hinge);
    }

    /** Sets the hinge, where there is one to set. */
    public BlockState withHinge(BlockState state, DoorHingeSide side) {
        EnumProperty<DoorHingeSide> hinge = hingeProperty();
        return hinge == null ? state : state.setValue(hinge, side);
    }

    /**
     * Whether this door keeps the redstone signal in its state.
     *
     * <p>True everywhere except a spring door, which cannot be held open by anything and so has
     * no signal worth remembering ({@link DoorStyle#springLoaded()}, D-36).
     */
    public boolean recordsSignal() {
        return true;
    }

    /** Whether a signal is holding this door open. Always false where none is recorded. */
    public boolean poweredOf(BlockState state) {
        return recordsSignal() && state.getValue(POWERED);
    }

    /** Stores the signal, where there is one to store. */
    public BlockState withPowered(BlockState state, boolean powered) {
        return recordsSignal() ? state.setValue(POWERED, powered) : state;
    }

    /** Sets the column, where there is one to set. */
    public BlockState withPart(BlockState state, int part) {
        IntegerProperty property = partProperty();
        return property == null ? state : state.setValue(property, part);
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

    /**
     * The lower half of column {@code PART 0}: the one position every part of a door agrees on.
     *
     * <p>It is the structure's origin (§3), reachable from any part without a block entity or a
     * search. What it is used for is a place to keep the one thing a door has to share -- the
     * clock its panels move on.
     */
    public BlockPos anchorOf(BlockState state, BlockPos pos) {
        return WideDoorGeometry.origin(
                lowerHalf(state, pos), layoutOf(state), partOf(state), swingOf(state));
    }

    /** The geometric layout matching a state. */
    public DoorLayout layoutOf(BlockState state) {
        return new DoorLayout(
                WideDoorGeometry.toCore(state.getValue(FACING)),
                width,
                mode,
                hingeOf(state) == DoorHingeSide.LEFT ? Hinge.LEFT : Hinge.RIGHT,
                style.motion());
    }

    // ------------------------------------------------------------------ shape

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        Swing swing = swingOf(state);
        Direction leaf = WideDoorGeometry.leafDirection(layoutOf(state), partOf(state), swing);

        if (style.slides()) {
            if (swing == Swing.CLOSED) {
                return (parksHere(state) ? LEAF_SHAPES : BACK_TRACK_SHAPES).get(leaf);
            }
            // Open, a whole leaf is stacked in the column it parks in and the rest are clear.
            // That empty shape is the doorway: it is what the player walks through.
            return parksHere(state) ? STACKED_SHAPES.get(leaf) : Shapes.empty();
        }

        boolean centred = style.springLoaded() && swing == Swing.CLOSED;
        return (centred ? CENTRED_LEAF_SHAPES : LEAF_SHAPES).get(leaf);
    }

    /**
     * Whether a sliding leaf ends up in this column.
     *
     * <p>Always false for a door that swings, which is what keeps the callers below reading as
     * one rule rather than two.
     */
    private boolean parksHere(BlockState state) {
        return style.slides() && layoutOf(state).parksHere(partOf(state));
    }

    /**
     * How far along the wall this column's panel sits, in blocks.
     *
     * <p>Zero while shut, and once open, the distance from this column to the one its leaf parks
     * in -- which is zero again for the column that does the parking. It is a signed count of
     * columns along {@code wallAxis}, not a direction, so that the only thing between shut and
     * open is a number to interpolate.
     */
    public float panelOffset(BlockState state) {
        if (!style.slides() || !swingOf(state).isOpen()) {
            return 0.0F;
        }
        DoorLayout layout = layoutOf(state);
        int part = partOf(state);
        return layout.hingePart(part) - part;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return switch (type) {
            // An open door is out of the way -- except the column a sliding leaf parked in,
            // which is as solid as it was before, only twice as thick.
            case LAND, AIR -> swingOf(state).isOpen() && !parksHere(state);
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
            BlockState state = withPart(defaultBlockState()
                    .setValue(FACING, context.getHorizontalDirection())
                    .setValue(HALF, DoubleBlockHalf.LOWER), clickedPart);

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
            // A spring door records no signal at all -- it cannot be held open -- so it does
            // not carry POWERED, and does not even go looking for one.
            return withPowered(withHinge(state, pivotFor(clickedPart, aim))
                    .setValue(swingProperty(), DoorSwing.CLOSED),
                    recordsSignal() && hasSignal(level, columns));
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
                BlockState lower = withPart(state, part).setValue(HALF, DoubleBlockHalf.LOWER);
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
        if (poweredOf(state)) {
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
        // Before the "is it open" guard, because a panel arrives home when the door shuts just
        // as much as when it opens. Behind that guard, closing left SLIDING set for good, and
        // a shut door stayed invisible past the 64 blocks a renderer reaches.
        if (style.slides()) {
            if (isMoving(state)) {
                // Every departure schedules the tick that ends it, and a door told to reverse
                // departs again -- leaving the first tick still in the queue, due in the middle
                // of the second journey. It used to end that journey: the flag cleared early,
                // the renderer let go, and the panel was left wherever it had got to, which
                // read as a small teleport. Now an early tick is sent away and comes back when
                // the panel is actually due.
                int remaining = remainingSlide(level, pos);
                if (remaining > 0) {
                    level.scheduleTick(pos, this, remaining);
                    return;
                }
                // The panel has arrived. Handing the drawing back to the block model is the
                // whole job here -- apply with the swing it has moves nothing and clears
                // SLIDING.
                apply(level, state, pos, swingOf(state), poweredOf(state), false);
            }
            return;
        }
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

        if (signal == poweredOf(state)) {
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
                && old.hingeOf(oldState) == hingeOf(state)
                && oldState.getValue(HALF) == state.getValue(HALF)
                && oldState.getValue(swingProperty()) == state.getValue(swingProperty())
                && old.partOf(oldState) == partOf(state);
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
                poweredOf(state), true);
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
                // Set on the way out and cleared on the way back, by the tick above. Doors
                // that swing have nothing to record and withMoving leaves them alone.
                BlockState lower = withMoving(withPowered(withPart(state, part)
                        .setValue(swingProperty(), WideDoorGeometry.toMinecraft(targetSwing)),
                        targetPowered)
                        .setValue(HALF, DoubleBlockHalf.LOWER), moves);
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

        // Two reasons to come back later, and a door needs at most one of them.
        //
        // A spring door is due to close. A door that left its frame has to poll, because the
        // signal source no longer touches it and no neighbour update will announce the signal
        // dropping. Polling starts regardless of what opened it: a door opened by hand must
        // still react to a plate afterwards.
        //
        // A door that stays where it is needs neither. That is every sliding door, and the
        // narrow swinging ones too, which were being woken five times a second for nothing.
        if (targetSwing.isOpen() && (style.springLoaded() || layout.movesBlocks())) {
            level.scheduleTick(to.get(0), this,
                    style.springLoaded() ? SPRING_CLOSE_TICKS : SIGNAL_POLL_TICKS);
        }

        // And a sliding door comes back to turn its own renderer off again. The departure is
        // written down first, so that the tick can tell an arrival from a journey that was
        // restarted after it was scheduled.
        if (style.slides()) {
            if (moves) {
                departed(level, to.get(0));
            }
            level.scheduleTick(to.get(0), this, SLIDE_HOLD_TICKS);
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
                // The target is the same door in another material -- same width, same mode --
                // so this door's own accessors describe its state correctly.
                BlockState lower = withPart(withPowered(withHinge(target.defaultBlockState()
                        .setValue(FACING, state.getValue(FACING)), hingeOf(state))
                        .setValue(swingProperty(), state.getValue(swingProperty())),
                        poweredOf(state)), part)
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
        if (mirror == Mirror.NONE) {
            return state;
        }
        // Mirroring swaps the hinge end -- on a door that has one. A door opening from the
        // middle is symmetric about that same axis, so the rotation alone is the whole answer.
        BlockState turned = rotate(state, mirror.getRotation(state.getValue(FACING)));
        return hingeProperty() == null ? turned : turned.cycle(HINGE);
    }
}
