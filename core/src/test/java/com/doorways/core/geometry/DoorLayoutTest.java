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
            List<Vec2i> closed = layout.footprint(Swing.CLOSED);
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
            for (Swing swing : Swing.values()) {
                List<Vec2i> fp = layout.footprint(swing);
                assertEquals(layout.width(), new HashSet<>(fp).size(),
                        desc(layout) + state(swing) + " has overlapping columns: " + fp);
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
            for (Swing swing : OPEN) {
                assertEquals(layout.footprint(Swing.CLOSED), layout.footprint(swing),
                        desc(layout) + state(swing) + " should not move blocks");
            }
            assertFalse(layout.movesBlocks(), desc(layout) + " movesBlocks()");
        });
    }

    @Test
    @DisplayName("§2.2: the three columns turn as one rigid leaf, the same way")
    void width3OpensAsOneRigidLeaf() {
        for (Facing facing : Facing.values()) {
            for (Hinge hinge : Hinge.values()) {
                DoorLayout layout = DoorLayout.of(facing, 3, hinge);
                Vec2i swing = layout.swingAxis(Swing.OUT).vec();
                Vec2i pivot = layout.closedOffset(hinge == Hinge.LEFT ? 0 : 2);

                List<Vec2i> open = layout.footprint(Swing.OUT);
                for (int part = 0; part < 3; part++) {
                    int distance = Math.abs(part - layout.hingePart(part));
                    assertEquals(pivot.plus(swing.times(distance)), open.get(part),
                            desc(layout) + " open, column " + part);
                }
                assertTrue(layout.movesBlocks(), desc(layout) + " must move blocks");
                assertEquals(2, layout.newlyOccupied(Swing.CLOSED, Swing.OUT).size(),
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
            Vec2i swing = layout.swingAxis(Swing.OUT).vec();

            assertEquals(0, layout.hingePart(0), "left half hinges at PART 0");
            assertEquals(0, layout.hingePart(1), "left half hinges at PART 0");
            assertEquals(3, layout.hingePart(2), "right half hinges at PART 3");
            assertEquals(3, layout.hingePart(3), "right half hinges at PART 3");

            List<Vec2i> open = layout.footprint(Swing.OUT);
            assertEquals(Vec2i.ZERO, open.get(0), desc(layout) + " PART 0 stays put");
            assertEquals(swing, open.get(1), desc(layout) + " PART 1 swings out");
            assertEquals(wall.times(3).plus(swing), open.get(2), desc(layout) + " PART 2 swings out");
            assertEquals(wall.times(3), open.get(3), desc(layout) + " PART 3 stays put");

            // The central opening is the two inner columns of the wall line.
            Set<Vec2i> released = new HashSet<>(layout.released(Swing.CLOSED, Swing.OUT));
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
                for (Swing swing : Swing.values()) {
                    assertEquals(left.footprint(swing), right.footprint(swing),
                            "SPLIT " + width + " " + facing + state(swing)
                                    + ": HINGE should change nothing");
                }
            }
        }
    }

    @Test
    @DisplayName("D-04: OUT moves away from the placer, never back towards -FACING")
    void outNeverSwingsBackwards() {
        forEachLayout(layout -> {
            for (Vec2i offset : layout.footprint(Swing.OUT)) {
                assertTrue(along(offset, layout.facing()) >= 0,
                        desc(layout) + " OUT: " + offset + " moved back towards -FACING");
            }
        });
    }

    @Test
    @DisplayName("BACK is the other half of the same rule: never past the wall line towards +FACING")
    void backNeverSwingsForwards() {
        forEachLayout(layout -> {
            for (Vec2i offset : layout.footprint(Swing.BACK)) {
                assertTrue(along(offset, layout.facing()) <= 0,
                        desc(layout) + " BACK: " + offset + " moved forwards towards +FACING");
            }
        });
    }

    @Test
    @DisplayName("the two swings are mirror images: same place along the wall, opposite side of it")
    void backMirrorsOut() {
        forEachLayout(layout -> {
            for (int part = 0; part < layout.width(); part++) {
                Vec2i out = layout.offset(part, Swing.OUT);
                Vec2i back = layout.offset(part, Swing.BACK);
                String where = desc(layout) + " PART " + part;

                assertEquals(along(out, layout.wallAxis()), along(back, layout.wallAxis()),
                        where + ": the two swings should share a place along the wall");
                assertEquals(-along(out, layout.facing()), along(back, layout.facing()),
                        where + ": the two swings should land on opposite sides of the wall");
            }
        });
    }

    @Test
    @DisplayName("a two-way door is as free to swing back as it is to swing out")
    void bothSwingsCostTheSame() {
        forEachLayout(layout -> {
            assertEquals(layout.newlyOccupied(Swing.CLOSED, Swing.OUT).size(),
                    layout.newlyOccupied(Swing.CLOSED, Swing.BACK).size(),
                    desc(layout) + ": one direction needs more room than the other");
        });
    }

    @Test
    @DisplayName("D-06: a door cannot block itself")
    void validationExcludesOwnPositions() {
        forEachLayout(layout -> {
            for (Swing[] move : MOVES) {
                Set<Vec2i> current = new HashSet<>(layout.footprint(move[0]));
                for (Vec2i pos : layout.newlyOccupied(move[0], move[1])) {
                    assertFalse(current.contains(pos),
                            desc(layout) + ": " + pos + " is already occupied by the door");
                }
            }
        });
    }

    @Test
    @DisplayName("what one direction takes, the other gives back")
    void releasedIsOccupiedInReverse() {
        forEachLayout(layout -> {
            for (Swing[] move : MOVES) {
                assertEquals(layout.newlyOccupied(move[1], move[0]),
                        layout.released(move[0], move[1]),
                        desc(layout) + " " + move[0] + " -> " + move[1]);
            }
        });
    }

    @Test
    @DisplayName("§3: any part can reconstruct the structure origin")
    void originRoundTrips() {
        Vec2i[] origins = {Vec2i.ZERO, new Vec2i(37, -12), new Vec2i(-500, 900)};
        forEachLayout(layout -> {
            for (Vec2i origin : origins) {
                for (Swing swing : Swing.values()) {
                    List<Vec2i> columns = layout.columnsAt(origin, swing);
                    for (int part = 0; part < layout.width(); part++) {
                        assertEquals(origin, layout.origin(columns.get(part), part, swing),
                                desc(layout) + state(swing) + " PART " + part);
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
                    for (Swing swing : Swing.values()) {
                        List<Vec2i> expected = base.footprint(swing).stream()
                                .map(v -> new Vec2i(-v.z(), v.x()))
                                .toList();
                        assertEquals(expected, turned.footprint(swing),
                                "width " + width + " " + hinge + state(swing)
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
        assertThrows(IllegalArgumentException.class,
                () -> DoorLayout.of(Facing.NORTH, 2, Hinge.LEFT).swingAxis(Swing.CLOSED),
                "a closed leaf has no swing axis");
    }

    // ----------------------------------------------------------------- helpers

    private static final Swing[] OPEN = {Swing.OUT, Swing.BACK};

    /**
     * The transitions the game performs. Every one has {@code CLOSED} at an end: a leaf swings
     * out of its frame or back into it, and never crosses straight from one side to the other.
     */
    private static final List<Swing[]> MOVES = List.of(
            new Swing[] {Swing.CLOSED, Swing.OUT},
            new Swing[] {Swing.OUT, Swing.CLOSED},
            new Swing[] {Swing.CLOSED, Swing.BACK},
            new Swing[] {Swing.BACK, Swing.CLOSED});

    private static void forEachLayout(Consumer<DoorLayout> body) {
        for (Facing facing : Facing.values()) {
            for (int width = 1; width <= 4; width++) {
                for (Hinge hinge : Hinge.values()) {
                    body.accept(DoorLayout.of(facing, width, hinge));
                }
            }
        }
    }

    /** How far {@code offset} reaches along {@code axis}, signed. */
    private static int along(Vec2i offset, Facing axis) {
        return offset.x() * axis.dx + offset.z() * axis.dz;
    }

    private static String desc(DoorLayout layout) {
        return "[" + layout.facing() + " w" + layout.width() + " " + layout.mode()
                + " " + layout.hinge() + "]";
    }

    private static String state(Swing swing) {
        return " " + swing;
    }
}
