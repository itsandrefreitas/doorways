package com.doorways.core.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the §15 cases that do not need the world.
 *
 * <p>The equivalent dependency-free check lives in {@code src/verify} and runs with
 * {@code ./gradlew :core:geometryCheck}. This version is the one {@code check} runs.
 */
class DoorLayoutTest {

    @Test
    @DisplayName("closed, the columns form a contiguous line along the wall")
    void closedIsContiguousWallLine() {
        forEachLayout(layout -> {
            Vec2i step = layout.wallAxis().vec();
            List<Vec2i> closed = layout.closedFootprint();
            for (int i = 0; i < layout.width(); i++) {
                assertEquals(step.times(i), closed.get(i),
                        desc(layout) + " closed, column " + i);
            }
        });
    }

    @Test
    @DisplayName("no leaf ever overlaps itself, in any state")
    void footprintNeverSelfOverlaps() {
        forEachLayout(layout -> {
            for (boolean open : BOTH) {
                List<Vec2i> fp = layout.footprint(open);
                assertEquals(layout.width(), new HashSet<>(fp).size(),
                        desc(layout) + state(open) + " has overlapping columns: " + fp);
            }
        });
    }

    @Test
    @DisplayName("D-07: widths 1 and 2 rotate inside their own block, moving nothing")
    void narrowDoorsNeverMoveBlocks() {
        forEachLayout(layout -> {
            if (layout.width() > 2) {
                return;
            }
            assertEquals(layout.closedFootprint(), layout.openFootprint(),
                    desc(layout) + " should not move blocks");
            assertFalse(layout.movesBlocks(), desc(layout) + " movesBlocks()");
        });
    }

    @Test
    @DisplayName("§2.2: the three columns turn as one rigid leaf, the same way")
    void width3OpensAsOneRigidLeaf() {
        for (Facing facing : Facing.values()) {
            for (Hinge hinge : Hinge.values()) {
                DoorLayout layout = DoorLayout.of(facing, 3, hinge);
                Vec2i swing = layout.swingAxis().vec();
                Vec2i pivot = layout.closedOffset(hinge == Hinge.LEFT ? 0 : 2);

                List<Vec2i> open = layout.openFootprint();
                for (int part = 0; part < 3; part++) {
                    int distance = Math.abs(part - layout.hingePart(part));
                    assertEquals(pivot.plus(swing.times(distance)), open.get(part),
                            desc(layout) + " open, column " + part);
                }
                assertTrue(layout.movesBlocks(), desc(layout) + " must move blocks");
                assertEquals(2, layout.newlyOccupied(true).size(),
                        desc(layout) + " should require 2 new columns");
            }
        }
    }

    @Test
    @DisplayName("§2.3: two rigid 2-block leaves, opening from the centre")
    void width4SplitsIntoTwoLeaves() {
        for (Facing facing : Facing.values()) {
            DoorLayout layout = DoorLayout.of(facing, 4, Hinge.LEFT);
            Vec2i wall = layout.wallAxis().vec();
            Vec2i swing = layout.swingAxis().vec();

            assertEquals(0, layout.hingePart(0), "left half hinges at PART 0");
            assertEquals(0, layout.hingePart(1), "left half hinges at PART 0");
            assertEquals(3, layout.hingePart(2), "right half hinges at PART 3");
            assertEquals(3, layout.hingePart(3), "right half hinges at PART 3");

            List<Vec2i> open = layout.openFootprint();
            assertEquals(Vec2i.ZERO, open.get(0), desc(layout) + " PART 0 stays put");
            assertEquals(swing, open.get(1), desc(layout) + " PART 1 swings out");
            assertEquals(wall.times(3).plus(swing), open.get(2), desc(layout) + " PART 2 swings out");
            assertEquals(wall.times(3), open.get(3), desc(layout) + " PART 3 stays put");

            // The central opening is the two inner columns of the wall line.
            Set<Vec2i> released = new HashSet<>(layout.released(true));
            assertEquals(Set.of(wall, wall.times(2)), released, desc(layout) + " central opening");
        }
    }

    @Test
    @DisplayName("under SINGLE the two hinges rotate opposite ways -- width 1 included")
    void singleHingesRotateOppositeWays() {
        for (Facing facing : Facing.values()) {
            for (int width = 1; width <= 3; width += 2) {
                DoorLayout left = DoorLayout.of(facing, width, Hinge.LEFT);
                DoorLayout right = DoorLayout.of(facing, width, Hinge.RIGHT);
                for (int part = 0; part < width; part++) {
                    assertTrue(left.pivotAtLowEnd(part), "width " + width + " LEFT, PART " + part);
                    assertFalse(right.pivotAtLowEnd(part), "width " + width + " RIGHT, PART " + part);
                }
            }
        }
    }

    @Test
    @DisplayName("at width 1 the pivot index cannot tell the hinges apart -- only the direction can")
    void width1HingeIsInvisibleInTheIndex() {
        DoorLayout left = DoorLayout.of(Facing.NORTH, 1, Hinge.LEFT);
        DoorLayout right = DoorLayout.of(Facing.NORTH, 1, Hinge.RIGHT);

        // Both ends are the same column, so hingePart() is 0 for both...
        assertEquals(0, left.hingePart(0));
        assertEquals(0, right.hingePart(0));
        // ...and deciding the rotation by hingePart() == 0 made the 1-wide door always open
        // to the same side. pivotAtLowEnd() is what has to separate them.
        assertTrue(left.pivotAtLowEnd(0));
        assertFalse(right.pivotAtLowEnd(0));
    }

    @Test
    @DisplayName("D-05: under SPLIT the hinges are the ends, so HINGE has no effect")
    void splitIgnoresHinge() {
        for (Facing facing : Facing.values()) {
            for (int width : new int[] {2, 4}) {
                DoorLayout left = new DoorLayout(facing, width, DoorMode.SPLIT, Hinge.LEFT);
                DoorLayout right = new DoorLayout(facing, width, DoorMode.SPLIT, Hinge.RIGHT);
                for (boolean open : BOTH) {
                    assertEquals(left.footprint(open), right.footprint(open),
                            "SPLIT " + width + " " + facing + state(open)
                                    + ": HINGE should change nothing");
                }
            }
        }
    }

    @Test
    @DisplayName("D-04: the leaf moves away from the placer, never back towards -FACING")
    void openNeverSwingsBackwards() {
        forEachLayout(layout -> {
            Vec2i front = layout.facing().vec();
            for (Vec2i offset : layout.openFootprint()) {
                int alongFacing = offset.x() * front.x() + offset.z() * front.z();
                assertTrue(alongFacing >= 0,
                        desc(layout) + " open: " + offset + " moved back towards -FACING");
            }
        });
    }

    @Test
    @DisplayName("D-06: a door cannot block itself")
    void validationExcludesOwnPositions() {
        forEachLayout(layout -> {
            for (boolean target : BOTH) {
                Set<Vec2i> current = new HashSet<>(layout.footprint(!target));
                for (Vec2i pos : layout.newlyOccupied(target)) {
                    assertFalse(current.contains(pos),
                            desc(layout) + ": " + pos + " is already occupied by the door");
                }
            }
        });
    }

    @Test
    @DisplayName("§3: any part can reconstruct the structure origin")
    void originRoundTrips() {
        Vec2i[] origins = {Vec2i.ZERO, new Vec2i(37, -12), new Vec2i(-500, 900)};
        forEachLayout(layout -> {
            for (Vec2i origin : origins) {
                for (boolean open : BOTH) {
                    List<Vec2i> columns = layout.columnsAt(origin, open);
                    for (int part = 0; part < layout.width(); part++) {
                        assertEquals(origin, layout.origin(columns.get(part), part, open),
                                desc(layout) + state(open) + " PART " + part);
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("§15: the four orientations are the same door, rotated")
    void allFourOrientationsAreRotations() {
        for (int width = 1; width <= 4; width++) {
            for (Hinge hinge : Hinge.values()) {
                for (Facing facing : Facing.values()) {
                    DoorLayout base = DoorLayout.of(facing, width, hinge);
                    DoorLayout turned = DoorLayout.of(facing.clockwise(), width, hinge);
                    for (boolean open : BOTH) {
                        List<Vec2i> expected = base.footprint(open).stream()
                                .map(v -> new Vec2i(-v.z(), v.x()))
                                .toList();
                        assertEquals(expected, turned.footprint(open),
                                "width " + width + " " + hinge + state(open)
                                        + ": " + facing + " rotated 90°");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("impossible layouts are rejected")
    void invalidLayoutsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DoorLayout.of(Facing.NORTH, 0, Hinge.LEFT), "width 0");
        assertThrows(IllegalArgumentException.class,
                () -> DoorLayout.of(Facing.NORTH, 5, Hinge.LEFT), "width 5");
        assertThrows(IllegalArgumentException.class,
                () -> new DoorLayout(Facing.NORTH, 3, DoorMode.SPLIT, Hinge.LEFT),
                "SPLIT with an odd width");
        assertThrows(IllegalArgumentException.class,
                () -> DoorLayout.of(Facing.NORTH, 2, Hinge.LEFT).closedOffset(2),
                "PART outside the range");
    }

    // ----------------------------------------------------------------- helpers

    private static final boolean[] BOTH = {false, true};

    private static void forEachLayout(Consumer<DoorLayout> body) {
        for (Facing facing : Facing.values()) {
            for (int width = 1; width <= 4; width++) {
                for (Hinge hinge : Hinge.values()) {
                    body.accept(DoorLayout.of(facing, width, hinge));
                }
            }
        }
    }

    private static String desc(DoorLayout layout) {
        return "[" + layout.facing() + " w" + layout.width() + " " + layout.mode()
                + " " + layout.hinge() + "]";
    }

    private static String state(boolean open) {
        return open ? " open" : " closed";
    }
}
