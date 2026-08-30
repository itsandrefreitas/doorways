package com.doorways.test;

import com.doorways.Doorways;
import com.doorways.block.DoorStyle;
import com.doorways.block.DoorSwing;
import com.doorways.block.DoorVariant;
import com.doorways.block.WideDoorBlock;
import com.doorways.block.WideDoorGeometry;
import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.Swing;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The test scenarios, shared by both loaders.
 *
 * <p>Each scenario covers a failure mode the pure-geometry assertions cannot reach.
 * {@link DoorLayout} is a pure function of coordinates; these tests cover the boundary with the
 * world, where a door that has moved is no longer where the world expects it.
 *
 * <p>The bodies use only vanilla API ({@link GameTestHelper}). Registration is what differs per
 * loader: an annotation on Fabric, a bus event on NeoForge. See DECISIONS.md, D-33.
 */
public final class DoorwayScenarios {

    /** Where the scenarios' stone floor sits. The door stands on the layer above. */
    private static final int FLOOR_Y = 1;

    /**
     * Opening and closing a 4-wide door by hand.
     *
     * <p>The simplest case that exercises displacement: all four columns must leave the frame
     * and come back, and the frame must be empty in between.
     */
    public static void opensAndClosesWidthFour(GameTestHelper helper) {
        Block door = door("oak", 4);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> closed = footprint(helper, door, origin, Swing.CLOSED);
        List<BlockPos> open = footprint(helper, door, origin, Swing.OUT);

        helper.startSequence()
                .thenExecute(() -> use(helper, closed.get(0)))
                .thenIdle(2)
                .thenExecute(() -> {
                    assertDoor(helper, door, open, Swing.OUT);
                    assertVacated(helper, closed, open);
                })
                .thenExecute(() -> use(helper, open.get(0)))
                .thenIdle(2)
                .thenExecute(() -> {
                    assertDoor(helper, door, closed, Swing.CLOSED);
                    assertVacated(helper, open, closed);
                })
                .thenSucceed();
    }

    /**
     * A pillar in the leaf's path prevents opening -- and <b>survives</b>.
     *
     * <p>Guards against the door writing itself over whatever occupies the destination, which
     * destroys those blocks with no drop. Two assertions, because either failure matters: the
     * door must not open, and the stone must still be there.
     */
    public static void pillarInPathSurvives(GameTestHelper helper) {
        Block door = door("oak", 3);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> closed = footprint(helper, door, origin, Swing.CLOSED);
        BlockPos blocked = newlyOccupied(helper, door, origin).get(0);
        helper.setBlock(blocked, Blocks.STONE);
        helper.setBlock(blocked.above(), Blocks.STONE);

        helper.startSequence()
                .thenExecute(() -> use(helper, closed.get(0)))
                .thenIdle(2)
                .thenExecute(() -> {
                    assertDoor(helper, door, closed, Swing.CLOSED);
                    helper.assertBlockPresent(Blocks.STONE, blocked);
                    helper.assertBlockPresent(Blocks.STONE, blocked.above());
                })
                .thenSucceed();
    }

    /**
     * Redstone opens and -- above all -- <b>closes</b>.
     *
     * <p>Closing is the half that broke. An open wide door leaves its frame, no longer has
     * blocks touching the signal source, and never received the neighbour update announcing the
     * signal dropping: it stayed open forever. The 5-tick polling fixes it, which is why this
     * test waits considerably more than one tick after removing the redstone block.
     */
    public static void redstoneOpensAndCloses(GameTestHelper helper) {
        Block door = door("oak", 3);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> closed = footprint(helper, door, origin, Swing.CLOSED);
        List<BlockPos> open = footprint(helper, door, origin, Swing.OUT);
        BlockPos power = closed.get(0).below();

        helper.startSequence()
                .thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                .thenIdle(4)
                .thenExecute(() -> assertDoor(helper, door, open, Swing.OUT))
                .thenExecute(() -> helper.setBlock(power, Blocks.AIR))
                .thenIdle(12)
                .thenExecute(() -> assertDoor(helper, door, closed, Swing.CLOSED))
                .thenSucceed();
    }

    /**
     * Scraping one copper column converts the whole door.
     *
     * <p>Reproduces what {@code AxeItem} does -- swap <b>one</b> block, keeping the properties --
     * and requires the other columns to follow. It covers the {@code onPlace} hook (D-31), so
     * that intercepting the item again, or changing the signature that identifies a conversion,
     * fails here.
     *
     * <p>It deliberately writes to a <b>middle</b> column and the <b>lower</b> half: the
     * propagation has to reach both sides and both heights.
     */
    public static void copperConversionSpreadsToWholeDoor(GameTestHelper helper) {
        Block exposed = door("exposed_copper", 3);
        Block scraped = door("copper", 3);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, exposed, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> closed = footprint(helper, exposed, origin, Swing.CLOSED);
        BlockPos middle = closed.get(1);

        helper.startSequence()
                .thenExecute(() -> {
                    BlockState was = helper.getBlockState(middle);
                    helper.setBlock(middle, scraped.withPropertiesOf(was));
                })
                .thenIdle(2)
                .thenExecute(() -> assertDoor(helper, scraped, closed, Swing.CLOSED))
                .thenSucceed();
    }

    /**
     * On a width-1 door the hinge decides which way the leaf turns.
     *
     * <p>Width 1 is the degenerate case where both ends of the door are the same column, and
     * that is exactly where the rotation logic once went wrong: both hinges gave the same
     * result.
     *
     * <p><b>A width-1 door does not move when it opens.</b> It behaves like a vanilla door: the
     * leaf rotates inside its own block, and {@code openOffset} returns zero because the pivot
     * is the only column there is. The hinge therefore shows in the <b>shape</b> --
     * {@code leafDirection} returns clockwise or counter-clockwise -- and never in the position,
     * which is why this test compares collision boxes rather than coordinates.
     */
    public static void widthOneRespectsHinge(GameTestHelper helper) {
        Block door = door("oak", 1);
        BlockPos left = new BlockPos(1, FLOOR_Y + 1, 3);
        BlockPos right = new BlockPos(5, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, left, Direction.SOUTH, DoorHingeSide.LEFT);
        place(helper, door, right, Direction.SOUTH, DoorHingeSide.RIGHT);

        helper.startSequence()
                .thenExecute(() -> {
                    use(helper, left);
                    use(helper, right);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    assertDoor(helper, door, List.of(left), Swing.OUT);
                    assertDoor(helper, door, List.of(right), Swing.OUT);
                    helper.assertTrue(!leafBounds(helper, left).equals(leafBounds(helper, right)),
                            "both hinges left the leaf on the same side of the block");
                })
                .thenSucceed();
    }

    /**
     * A spring door goes away from whoever pushes it, on either side.
     *
     * <p>This is the whole point of the second swing, and the only test that can catch the sign
     * of the comparison being wrong: get it backwards and the door still opens, still closes,
     * and still looks right -- it just opens into your face.
     *
     * <p>Width 2 on purpose. It does not displace, so nothing here depends on the leaf finding
     * room; what is being tested is the choice of direction and nothing else.
     */
    public static void saloonSwingsAwayFromThePlayer(GameTestHelper helper) {
        Block door = door("oak", 2, DoorStyle.SALOON);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> columns = footprint(helper, door, origin, Swing.CLOSED);
        BlockPos behind = origin.north();
        BlockPos infront = origin.south();

        helper.startSequence()
                .thenExecute(() -> useFrom(helper, origin, behind))
                .thenIdle(2)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.OUT))
                // Pushing an open door shuts it, whichever side you are on.
                .thenExecute(() -> useFrom(helper, origin, behind))
                .thenIdle(2)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.CLOSED))
                .thenExecute(() -> useFrom(helper, origin, infront))
                .thenIdle(2)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.BACK))
                .thenSucceed();
    }

    /**
     * The hinge does not move when the door is pushed the other way.
     *
     * <p>This one shipped broken and was caught by eye, not by a test. Deriving the leaf's
     * rotation from the swing direction instead of from the pivot sends the open leaf to the far
     * end of its column; on a two-leaf door the halves then meet in the middle of the opening,
     * hinged on nothing, and every assertion about state still passes.
     *
     * <p>What the two swings change is which columns the door occupies, and that comes from the
     * geometry. Width 2 does not translate at all (D-07), so once open the two swings put the
     * leaf in exactly the same box -- which is what makes it a usable assertion here.
     */
    public static void saloonKeepsItsHingeBothWays(GameTestHelper helper) {
        Block door = door("oak", 2, DoorStyle.SALOON);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        AABB[] swung = new AABB[2];

        helper.startSequence()
                .thenExecute(() -> useFrom(helper, origin, origin.north()))
                .thenIdle(2)
                .thenExecute(() -> swung[0] = leafBounds(helper, origin))
                .thenExecute(() -> useFrom(helper, origin, origin.north()))
                .thenIdle(2)
                .thenExecute(() -> useFrom(helper, origin, origin.south()))
                .thenIdle(2)
                .thenExecute(() -> {
                    swung[1] = leafBounds(helper, origin);
                    helper.assertTrue(swung[0].equals(swung[1]),
                            "the leaf changed ends with the swing: " + swung[0] + " then "
                                    + swung[1] + " -- the hinge is being taken from the direction");
                })
                .thenSucceed();
    }

    /** The spring brings the leaf back on its own, with nobody touching it. */
    public static void saloonClosesItself(GameTestHelper helper) {
        Block door = door("oak", 2, DoorStyle.SALOON);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> columns = footprint(helper, door, origin, Swing.CLOSED);

        helper.startSequence()
                .thenExecute(() -> useFrom(helper, origin, origin.north()))
                .thenIdle(2)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.OUT))
                .thenIdle(50)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.CLOSED))
                .thenSucceed();
    }

    /**
     * A blocked swing refuses. It does <b>not</b> quietly go the other way.
     *
     * <p>Swinging the other way would be friendlier and is the tempting fix. It is also
     * unpredictable: the player cannot see what is behind the door, so a door that occasionally
     * opens towards them for reasons they cannot observe is worse than one that will not budge.
     *
     * <p>Width 4, because it is the only saloon width that displaces and therefore the only one
     * that can be obstructed at all.
     */
    public static void saloonRefusesTheBlockedSide(GameTestHelper helper) {
        Block door = door("oak", 4, DoorStyle.SALOON);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> closed = footprint(helper, door, origin, Swing.CLOSED);
        BlockPos blocked = newlyOccupied(helper, door, origin).get(0);
        helper.setBlock(blocked, Blocks.STONE);
        helper.setBlock(blocked.above(), Blocks.STONE);

        helper.startSequence()
                // Standing behind it asks for the swing that is obstructed.
                .thenExecute(() -> useFrom(helper, origin, origin.north()))
                .thenIdle(2)
                .thenExecute(() -> {
                    assertDoor(helper, door, closed, Swing.CLOSED);
                    helper.assertBlockPresent(Blocks.STONE, blocked);
                })
                .thenSucceed();
    }

    /**
     * Redstone does nothing to a spring door.
     *
     * <p>A spring hinge has no latch, so there is no position a signal could hold it in. The
     * same redstone block that opens every other door in the mod must leave this one shut.
     */
    public static void saloonIgnoresRedstone(GameTestHelper helper) {
        Block door = door("oak", 2, DoorStyle.SALOON);
        BlockPos origin = new BlockPos(3, FLOOR_Y + 1, 3);
        floor(helper, 8, 8);
        place(helper, door, origin, Direction.SOUTH, DoorHingeSide.LEFT);

        List<BlockPos> columns = footprint(helper, door, origin, Swing.CLOSED);

        helper.startSequence()
                .thenExecute(() -> helper.setBlock(origin.below(), Blocks.REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> assertDoor(helper, door, columns, Swing.CLOSED))
                .thenSucceed();
    }

    // ---------------------------------------------------------------- helpers

    /** Looks up the registered solid door for this material and width. */
    private static Block door(String material, int width) {
        return door(material, width, DoorStyle.SOLID);
    }

    /** Looks up the registered door for this material, width and style. */
    private static Block door(String material, int width, DoorStyle style) {
        DoorVariant variant = DoorVariant.find(material, width, style)
                .orElseThrow(() -> new IllegalStateException(
                        "no such variant: " + material + " " + width + " " + style));
        Block block = BuiltInRegistries.BLOCK.getValue(variant.blockKey(Doorways.MOD_ID));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("door not registered: " + variant.name());
        }
        return block;
    }

    /** A stone floor, so the doors have something to stand on and the scenario repeats. */
    private static void floor(GameTestHelper helper, int sizeX, int sizeZ) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
            }
        }
    }

    /**
     * Places a complete door through the block's own path.
     *
     * <p>Column 0 is written and {@code setPlacedBy} places the rest, which is what the item
     * does in game. Assembling the columns directly would bypass the transaction guard and
     * produce a door the game never creates.
     */
    private static void place(GameTestHelper helper, Block door, BlockPos origin,
                              Direction facing, DoorHingeSide hinge) {
        BlockState lower = door.defaultBlockState()
                .setValue(WideDoorBlock.FACING, facing)
                .setValue(WideDoorBlock.HINGE, hinge)
                .setValue(WideDoorBlock.SWING, DoorSwing.CLOSED)
                .setValue(WideDoorBlock.POWERED, false)
                .setValue(WideDoorBlock.PART, 0)
                .setValue(WideDoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockPos absolute = helper.absolutePos(origin);
        helper.getLevel().setBlock(absolute, lower, Block.UPDATE_ALL);
        door.setPlacedBy(helper.getLevel(), absolute, lower, null, ItemStack.EMPTY);
    }

    /** The columns the door occupies in a given state, in test coordinates. */
    private static List<BlockPos> footprint(GameTestHelper helper, Block door, BlockPos origin,
                                            Swing swing) {
        return WideDoorGeometry.columns(origin, layout(helper, door, origin), swing);
    }

    /** The columns the door will occupy once open, and which are free right now. */
    private static List<BlockPos> newlyOccupied(GameTestHelper helper, Block door,
                                                BlockPos origin) {
        return WideDoorGeometry.newlyOccupied(origin, layout(helper, door, origin),
                Swing.CLOSED, Swing.OUT);
    }

    private static DoorLayout layout(GameTestHelper helper, Block door, BlockPos origin) {
        return ((WideDoorBlock) door).layoutOf(helper.getBlockState(origin));
    }

    /** Requires the whole door, both halves, in the requested state. */
    private static void assertDoor(GameTestHelper helper, Block door, List<BlockPos> columns,
                                   Swing swing) {
        for (BlockPos column : columns) {
            for (BlockPos half : List.of(column, column.above())) {
                helper.assertBlockPresent(door, half);
                helper.assertTrue(WideDoorBlock.swingOf(helper.getBlockState(half)) == swing,
                        "door at " + half + " should be " + swing);
            }
        }
    }

    /** Requires whatever the door left behind to be empty. */
    private static void assertVacated(GameTestHelper helper, List<BlockPos> from,
                                      List<BlockPos> to) {
        for (BlockPos column : from) {
            if (to.contains(column)) {
                continue;
            }
            helper.assertBlockPresent(Blocks.AIR, column);
            helper.assertBlockPresent(Blocks.AIR, column.above());
        }
    }

    /**
     * The box the leaf occupies inside its block.
     *
     * <p>This is where the hinge shows on a door that does not move: the leaf sits against one
     * edge or the opposite one, depending on which way it turned.
     */
    private static AABB leafBounds(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos)
                .getShape(helper.getLevel(), helper.absolutePos(pos))
                .bounds();
    }

    /** Clicks the door with an empty hand, as a player would. */
    private static void use(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.useBlock(pos, player);
    }

    /**
     * The same click, from a chosen spot.
     *
     * <p>Where the player stands is what decides a spring door's direction, so it cannot be left
     * to wherever the mock player happens to spawn.
     */
    private static void useFrom(GameTestHelper helper, BlockPos door, BlockPos standing) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 at = Vec3.atCenterOf(helper.absolutePos(standing));
        player.setPos(at.x, at.y, at.z);
        helper.useBlock(door, player);
    }

    private DoorwayScenarios() {}
}
