package com.doorways.core.geometry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Geometry verification, with no Minecraft and no external dependencies.
 *
 * <p>Covers the §15 cases that do not need the world, and stands in as the geometric source of
 * truth in place of the specification's corrupted ASCII diagrams (DECISIONS.md, D-18).
 *
 * <p>Lives in its own source set because it is a program with {@code main()}, not a JUnit
 * test. It runs with nothing but a JDK -- no Gradle and no Minecraft.
 */
public final class GeometryCheck {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        checkClosedIsContiguousWallLine();
        checkFootprintNeverSelfOverlaps();
        checkWidth1And2NeverMoveBlocks();
        checkWidth3OpensAsOneRigidLeaf();
        checkWidth4SplitsIntoTwoLeaves();
        checkSplitIgnoresHinge();
        checkOpenNeverSwingsBackwards();
        checkValidationExcludesOwnPositions();
        checkOriginRoundTrips();
        checkAllFourOrientationsAreRotations();
        checkInvalidLayoutsRejected();

        printOffsetTables();

        System.out.println();
        System.out.println("=".repeat(72));
        if (failures.isEmpty()) {
            System.out.println("OK - " + passed + " assertions passed.");
        } else {
            System.out.println("FAILED - " + failures.size() + " of " + (passed + failures.size()));
            failures.forEach(f -> System.out.println("  x " + f));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- cases

    /** Closed, the columns form a contiguous line along the wall. */
    private static void checkClosedIsContiguousWallLine() {
        forEachLayout(layout -> {
            Vec2i step = layout.wallAxis().vec();
            List<Vec2i> closed = layout.closedFootprint();
            for (int i = 0; i < layout.width(); i++) {
                check(closed.get(i).equals(step.times(i)),
                        desc(layout) + " closed: column " + i + " should be at " + step.times(i)
                                + ", was at " + closed.get(i));
            }
        });
    }

    /** No leaf ever overlaps itself, in any state. */
    private static void checkFootprintNeverSelfOverlaps() {
        forEachLayout(layout -> {
            for (boolean open : new boolean[] {false, true}) {
                List<Vec2i> fp = layout.footprint(open);
                check(new HashSet<>(fp).size() == layout.width(),
                        desc(layout) + (open ? " open" : " closed")
                                + ": overlapping columns in " + fp);
            }
        });
    }

    /** D-07: widths 1 and 2 rotate inside their own block, like a vanilla door. */
    private static void checkWidth1And2NeverMoveBlocks() {
        forEachLayout(layout -> {
            if (layout.width() > 2) return;
            check(layout.closedFootprint().equals(layout.openFootprint()),
                    desc(layout) + ": width " + layout.width() + " should not move blocks, but "
                            + layout.closedFootprint() + " -> " + layout.openFootprint());
            check(!layout.movesBlocks(), desc(layout) + ": movesBlocks() should be false");
        });
    }

    /** §2.2: the three columns turn as one rigid leaf, the same way. */
    private static void checkWidth3OpensAsOneRigidLeaf() {
        for (Facing facing : Facing.values()) {
            for (Hinge hinge : Hinge.values()) {
                DoorLayout layout = DoorLayout.of(facing, 3, hinge);
                Vec2i swing = layout.swingAxis().vec();
                Vec2i pivot = layout.closedOffset(hinge == Hinge.LEFT ? 0 : 2);

                List<Vec2i> open = layout.openFootprint();
                for (int part = 0; part < 3; part++) {
                    int distance = Math.abs(part - layout.hingePart(part));
                    Vec2i expected = pivot.plus(swing.times(distance));
                    check(open.get(part).equals(expected),
                            desc(layout) + " open: column " + part + " expected at " + expected
                                    + ", was at " + open.get(part));
                }
                check(layout.movesBlocks(), desc(layout) + ": width 3 must move blocks");
                check(layout.newlyOccupied(true).size() == 2,
                        desc(layout) + ": opening should require 2 new columns, required "
                                + layout.newlyOccupied(true));
            }
        }
    }

    /** §2.3: two rigid 2-block leaves, each its own way, opening from the centre. */
    private static void checkWidth4SplitsIntoTwoLeaves() {
        for (Facing facing : Facing.values()) {
            DoorLayout layout = DoorLayout.of(facing, 4, Hinge.LEFT);
            Vec2i wall = layout.wallAxis().vec();
            Vec2i swing = layout.swingAxis().vec();

            check(layout.hingePart(0) == 0 && layout.hingePart(1) == 0,
                    desc(layout) + ": left half should hinge at PART 0");
            check(layout.hingePart(2) == 3 && layout.hingePart(3) == 3,
                    desc(layout) + ": right half should hinge at PART 3");

            List<Vec2i> open = layout.openFootprint();
            check(open.get(0).equals(Vec2i.ZERO), desc(layout) + ": PART 0 should stay put");
            check(open.get(1).equals(swing), desc(layout) + ": PART 1 should swing to " + swing);
            check(open.get(2).equals(wall.times(3).plus(swing)),
                    desc(layout) + ": PART 2 should swing to " + wall.times(3).plus(swing));
            check(open.get(3).equals(wall.times(3)), desc(layout) + ": PART 3 should stay put");

            // The gap opened at the centre is the two inner columns of the wall line.
            List<Vec2i> released = layout.released(true);
            check(released.size() == 2 && released.contains(wall) && released.contains(wall.times(2)),
                    desc(layout) + ": the central gap should release " + wall + " and "
                            + wall.times(2) + ", released " + released);
        }
    }

    /** D-05: under SPLIT the hinges are the ends -- HINGE cannot have any effect. */
    private static void checkSplitIgnoresHinge() {
        for (Facing facing : Facing.values()) {
            for (int width : new int[] {2, 4}) {
                DoorLayout left = new DoorLayout(facing, width, DoorMode.SPLIT, Hinge.LEFT);
                DoorLayout right = new DoorLayout(facing, width, DoorMode.SPLIT, Hinge.RIGHT);
                for (boolean open : new boolean[] {false, true}) {
                    check(left.footprint(open).equals(right.footprint(open)),
                            "SPLIT " + width + " " + facing + (open ? " open" : " closed")
                                    + ": HINGE should change nothing, but "
                                    + left.footprint(open) + " != " + right.footprint(open));
                }
            }
        }
    }

    /** D-04: the leaf always moves away from the placer -- never back towards -FACING. */
    private static void checkOpenNeverSwingsBackwards() {
        forEachLayout(layout -> {
            Vec2i front = layout.facing().vec();
            for (Vec2i offset : layout.openFootprint()) {
                int alongFacing = offset.x() * front.x() + offset.z() * front.z();
                check(alongFacing >= 0,
                        desc(layout) + " open: " + offset + " moved back towards -FACING ("
                                + layout.facing().opposite() + "), should swing towards "
                                + layout.swingAxis());
            }
        });
    }

    /** D-06: a door cannot block itself. */
    private static void checkValidationExcludesOwnPositions() {
        forEachLayout(layout -> {
            for (boolean target : new boolean[] {true, false}) {
                Set<Vec2i> current = new HashSet<>(layout.footprint(!target));
                for (Vec2i pos : layout.newlyOccupied(target)) {
                    check(!current.contains(pos),
                            desc(layout) + ": " + pos + " is already occupied by the door and "
                                    + "should not be validated when "
                                    + (target ? "opening" : "closing"));
                }
                check(new HashSet<>(layout.footprint(target)).size()
                                == layout.newlyOccupied(target).size()
                                        + intersectionSize(layout, target),
                        desc(layout) + ": inconsistent position count");
            }
        });
    }

    /** §3: any part must be able to reconstruct the structure origin. */
    private static void checkOriginRoundTrips() {
        Vec2i[] origins = {Vec2i.ZERO, new Vec2i(37, -12), new Vec2i(-500, 900)};
        forEachLayout(layout -> {
            for (Vec2i origin : origins) {
                for (boolean open : new boolean[] {false, true}) {
                    List<Vec2i> columns = layout.columnsAt(origin, open);
                    for (int part = 0; part < layout.width(); part++) {
                        Vec2i recovered = layout.origin(columns.get(part), part, open);
                        check(recovered.equals(origin),
                                desc(layout) + (open ? " open" : " closed") + ": PART " + part
                                        + " at " + columns.get(part) + " reconstructed origin "
                                        + recovered + ", expected " + origin);
                    }
                }
            }
        });
    }

    /** §15: the four orientations must be the same door, rotated. */
    private static void checkAllFourOrientationsAreRotations() {
        for (int width = 1; width <= 4; width++) {
            for (Hinge hinge : Hinge.values()) {
                for (Facing facing : Facing.values()) {
                    DoorLayout base = DoorLayout.of(facing, width, hinge);
                    DoorLayout turned = DoorLayout.of(facing.clockwise(), width, hinge);
                    for (boolean open : new boolean[] {false, true}) {
                        List<Vec2i> expected = base.footprint(open).stream()
                                .map(GeometryCheck::rotateClockwise).toList();
                        check(turned.footprint(open).equals(expected),
                                "width " + width + " " + hinge + (open ? " open" : " closed")
                                        + ": " + facing + " rotated should give " + expected
                                        + ", gave " + turned.footprint(open));
                    }
                }
            }
        }
    }

    private static void checkInvalidLayoutsRejected() {
        checkThrows(() -> DoorLayout.of(Facing.NORTH, 0, Hinge.LEFT), "width 0");
        checkThrows(() -> DoorLayout.of(Facing.NORTH, 5, Hinge.LEFT), "width 5");
        checkThrows(() -> new DoorLayout(Facing.NORTH, 3, DoorMode.SPLIT, Hinge.LEFT),
                "SPLIT with an odd width");
        checkThrows(() -> DoorLayout.of(Facing.NORTH, 2, Hinge.LEFT).closedOffset(2),
                "PART outside the range");
    }

    // ---------------------------------------------------------------- report

    /** Replaces the specification's corrupted ASCII diagrams (D-18). */
    private static void printOffsetTables() {
        System.out.println("Offset tables - relative to column PART 0 when closed");
        System.out.println("R = wall axis (FACING turned 90° CW) - S = swing axis (FACING)");
        for (int width = 1; width <= 4; width++) {
            DoorMode mode = DoorMode.defaultFor(width);
            for (Hinge hinge : Hinge.values()) {
                if (mode == DoorMode.SPLIT && hinge == Hinge.RIGHT) continue; // irrelevant
                DoorLayout north = DoorLayout.of(Facing.NORTH, width, hinge);
                System.out.println();
                System.out.printf("  width %d - %s%s%n", width, mode,
                        mode == DoorMode.SINGLE ? " - hinge " + hinge : "");
                System.out.println("    closed : " + symbolic(north, false));
                System.out.println("    open   : " + symbolic(north, true));
                System.out.println("    moves  : " + (north.movesBlocks()
                        ? north.newlyOccupied(true).size() + " new columns to validate"
                        : "nothing (rotates inside its own block)"));
            }
        }
    }

    /** Prints offsets in terms of R and S, valid for any FACING. */
    private static String symbolic(DoorLayout layout, boolean open) {
        Vec2i wall = layout.wallAxis().vec();
        Vec2i swing = layout.swingAxis().vec();
        List<String> terms = new ArrayList<>();
        for (Vec2i offset : layout.footprint(open)) {
            int r = offset.x() * wall.x() + offset.z() * wall.z();
            int s = offset.x() * swing.x() + offset.z() * swing.z();
            List<String> parts = new ArrayList<>();
            if (!term(r, "R").isEmpty()) parts.add(term(r, "R"));
            if (!term(s, "S").isEmpty()) parts.add(term(s, "S"));
            terms.add(parts.isEmpty() ? "0" : String.join("+", parts));
        }
        return String.join("   ", terms);
    }

    private static String term(int n, String axis) {
        if (n == 0) return "";
        return (n == 1 ? "" : n + "") + axis;
    }

    // --------------------------------------------------------------- helpers

    private static Vec2i rotateClockwise(Vec2i v) {
        return new Vec2i(-v.z(), v.x());
    }

    private static int intersectionSize(DoorLayout layout, boolean target) {
        Set<Vec2i> current = new HashSet<>(layout.footprint(!target));
        int shared = 0;
        for (Vec2i pos : new HashSet<>(layout.footprint(target))) {
            if (current.contains(pos)) shared++;
        }
        return shared;
    }

    private static void forEachLayout(java.util.function.Consumer<DoorLayout> body) {
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

    private static void check(boolean condition, String message) {
        if (condition) passed++;
        else failures.add(message);
    }

    private static void checkThrows(Runnable body, String what) {
        try {
            body.run();
            failures.add("should have rejected: " + what);
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    private GeometryCheck() {}
}
