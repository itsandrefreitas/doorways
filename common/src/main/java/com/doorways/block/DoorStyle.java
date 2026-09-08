package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.DoorMode;
import com.doorways.core.geometry.Motion;

/**
 * What a door looks like, and which materials and widths it exists in.
 *
 * <p>Style was a boolean while there were only two options. It is an enum because the newer
 * styles are not available everywhere: glass and bookshelf doors have no material to vary, and
 * saloon doors only make sense at the widths that split into two leaves.
 *
 * <p>Each constant answers three questions: which materials it accepts, which widths, and how
 * the id and model names are built. The generator in {@code tools/gen_assets.py} mirrors the
 * naming; the two must agree or a door ends up pointing at a model that was never written.
 */
public enum DoorStyle {

    /** A full panel of the material. */
    SOLID("", true),

    /** Glass in the upper half only; the lower half reuses the solid model. */
    GLAZED("_glass", true),

    /** Glass throughout, in an iron frame. One door per width, with no material to vary. */
    FULL_GLASS("", false),

    /**
     * A short slatted panel with a gap above and below, in the western idiom.
     *
     * <p>The only style on a spring hinge, which is what lets it swing both ways and close by
     * itself. See {@link #springLoaded()}.
     */
    SALOON("_saloon", true),

    /** A wall of books. One door per width. */
    BOOKSHELF("", false),

    /**
     * A plain paper panel in a lacquered border, running on two tracks.
     *
     * <p>Fusuma rather than shoji, and the difference matters: a shoji is its lattice, and a
     * fusuma is a clear field with nothing on it -- which is what makes it the thing people
     * paint. The lattice is left out on purpose.
     *
     * <p>The first style that slides rather than swings. Its panels hide behind each other
     * instead of leaving the doorway, which is why it needs no space beside it and can never be
     * blocked. See {@link Motion#SLIDE}.
     */
    FUSUMA("_fusuma", true),

    /**
     * A sheet of glass in a slim frame, on the same two tracks.
     *
     * <p>Not a shoji with glass where the paper goes: a shoji is its lattice, and a glass door
     * that slides has no business carrying one. One door per width.
     */
    SLIDING_GLASS("_sliding", false),

    /**
     * Cut stone in a heavy frame, narrow and available <b>three blocks tall</b>.
     *
     * <p>The first style with a height of its own, and the reason the vertical axis exists. It
     * stops at 2 columns on purpose: a leaf of cut stone four blocks across, turning on a hinge,
     * is not a door anybody would believe.
     *
     * <p>No infix, because the material already says stone -- {@code cobblestone_doorway_1}
     * reads better than {@code cobblestone_stone_doorway_1}, and no solid door is made of
     * cobblestone, so nothing collides.
     */
    STONE("", true);

    private final String infix;
    private final boolean perMaterial;

    DoorStyle(String infix, boolean perMaterial) {
        this.infix = infix;
        this.perMaterial = perMaterial;
    }

    /** Whether this style exists once per material, or once in total. */
    public boolean perMaterial() {
        return perMaterial;
    }

    /**
     * Whether the leaves hang on a double-acting spring hinge.
     *
     * <p>One mechanism, three consequences, which is why they are one flag and not three: the
     * door swings both ways, it returns to its frame on its own, and it ignores redstone.
     *
     * <p>That last one is not a shortcut. A spring hinge has no latch -- there is no position it
     * can be made to stay in. A signal saying "hold this open" and a spring saying "come back"
     * would simply fight, and whichever won would make the other look broken.
     */
    public boolean springLoaded() {
        return this == SALOON;
    }

    /** How the leaves get out of the way. */
    public Motion motion() {
        return this == FUSUMA || this == SLIDING_GLASS ? Motion.SLIDE : Motion.SWING;
    }

    public boolean slides() {
        return motion() == Motion.SLIDE;
    }

    /**
     * Whether this style's blocks are drawn by the renderer at all times, rather than by the
     * chunk's mesh whenever they are standing still.
     *
     * <p>True for the one style whose panels are <b>see-through</b>, and for that reason alone.
     * Every other sliding door hands its drawing back and forth between the mesh and the
     * renderer -- mesh at rest, renderer while travelling -- and the two draw it identically,
     * so the handover cannot be seen. On glass they do not:
     *
     * <ul>
     *   <li>the mesh drops the faces where two panels meet, because on an opaque door nobody
     *       can see them. Through glass those faces are exactly what shows there is a second
     *       panel behind the first, so arriving turned two panes into one;</li>
     *   <li>the mesh drops faces against neighbouring blocks, and the renderer -- which is shown
     *       a world containing only the panel -- keeps them. Every dropped layer is one less
     *       thing to see through;</li>
     *   <li>and a chunk's mesh is rebuilt a frame or three after the state that invalidated it,
     *       so at each handover the two briefly draw together, or neither draws. Blended twice,
     *       glass goes momentarily solid; drawn by neither, it blinks out.</li>
     * </ul>
     *
     * <p>Nothing bridges that: the two paths are different renderers with different information.
     * So this style never crosses between them. The cost is a block entity drawn every frame
     * instead of geometry batched into the chunk, which is what a chest costs, and it buys a
     * door that looks the same standing still as it does moving.
     */
    public boolean drawnByRenderer() {
        return this == SLIDING_GLASS;
    }

    /**
     * Whether this style stands in a doorway of its own masonry.
     *
     * <p>The jambs are drawn by the model and are <b>solid</b>: they are the one piece of a door
     * in this mod that a player can walk into. Everything else about a door is the leaf.
     */
    public boolean jambed() {
        return this == STONE;
    }

    /**
     * Whether a painting can be applied to this style.
     *
     * <p>Only the papered one. A fusuma's panel is the canvas of the room and has been painted
     * for as long as there have been fusuma; a pane of glass is not a canvas, and a door with a
     * hinge is a different object with a frame around its face.
     */
    public boolean paintable() {
        return this == FUSUMA;
    }

    /**
     * How this style divides at a given width.
     *
     * <p>Sliding doors do not get the default: a sliding leaf is always
     * {@link DoorLayout#SLIDING_LEAF} panels, so two columns make one leaf and four make two.
     * Every other style follows the table in §2.
     */
    public DoorMode modeFor(int width) {
        if (!slides()) {
            return DoorMode.defaultFor(width);
        }
        return width == DoorLayout.SLIDING_LEAF ? DoorMode.SINGLE : DoorMode.SPLIT;
    }

    /** Whether a door of this style exists at the given width. */
    public boolean allowsWidth(int width) {
        // A saloon door is two swinging leaves, and a sliding one is leaves of two panels. Both
        // need an even width: at an odd one, one leaf would come out wider than the other.
        if (this == SALOON || slides()) {
            return width % 2 == 0;
        }
        // Stone stops at two columns -- see the constant.
        if (this == STONE) {
            return width <= 2;
        }
        return true;
    }

    /**
     * The heights this style comes in, shortest first.
     *
     * <p>Two is what every door was and what nearly every door still is. A style listing more
     * than one height becomes that many separate blocks, exactly as a style listing more than
     * one width does -- height is an axis of the catalogue, not a property of a door.
     */
    public int[] heights() {
        return this == STONE
                ? new int[] {WideDoorBlock.DEFAULT_HEIGHT, 3}
                : new int[] {WideDoorBlock.DEFAULT_HEIGHT};
    }

    /**
     * The id stem: {@code oak_saloon_doorway_2}, {@code glass_doorway_1},
     * {@code cobblestone_doorway_2x3}.
     *
     * <p>Styles that are not per-material drop the material from the name entirely -- the
     * material of a glass door is glass, and repeating it reads badly.
     *
     * <p>The height appears only when it is not the usual two, so that every id written before
     * there were gates still names the same block. {@code 2x3} is width by height, in that
     * order, which is how a doorway is measured everywhere else.
     */
    public String name(String material, int width, int height) {
        String size = height == WideDoorBlock.DEFAULT_HEIGHT
                ? String.valueOf(width)
                : width + "x" + height;
        return material + infix + "_doorway_" + size;
    }

    /**
     * The kind of row: the one on the ground, the one at the head, or any in between.
     *
     * <p>Three kinds however tall the door is -- four where the head is arched. A five-row gate
     * has three middle rows and they are the same row three times: the model does not multiply
     * with the height, which is what makes an arbitrary height affordable in files as well as in
     * states.
     */
    public String rowKind(int row, int height) {
        if (row == 0) {
            return "bottom";
        }
        if (row < height - 1) {
            return "middle";
        }
        return arched(height) ? "arch" : "top";
    }

    /**
     * Whether this style's top row carries a semicircular head at this height.
     *
     * <p>Only where there is room to walk under one. A round head over a two-row door leaves
     * 1.5 blocks of clear height at the crown and a player is 1.8 tall -- a door you cannot
     * walk through standing is not a door, however handsome. At three rows the crown clears
     * two and a half blocks (D-40).
     *
     * <p>It is a row <b>kind</b> and not a flag on the top row, because the two heights share
     * their model otherwise: arching "the top row" would have arched the two-row door as well.
     */
    public boolean arched(int height) {
        return this == STONE && height > WideDoorBlock.DEFAULT_HEIGHT;
    }

    /**
     * The model stem for one row of one column.
     *
     * <p>{@link #GLAZED} is the exception: only its top row differs from {@link #SOLID}, so the
     * one below reuses the solid model rather than duplicating it per material.
     */
    public String modelStem(String material, String rowKind, String role) {
        String styled = this == GLAZED && !rowKind.equals("top") ? "" : infix;
        return material + styled + "_doorway_" + rowKind + "_" + role;
    }

    /**
     * The model stem for one half of one column, in the frame or swung out of it.
     *
     * <p>Every style needs both, for the reason vanilla ships {@code door_bottom_left} and
     * {@code door_bottom_left_open}: swinging turns the leaf the other way about its hinge,
     * which reverses which end of the texture faces the frame. One model for both states puts
     * the ironwork at the free end of the leaf as soon as it opens.
     *
     * <p>A spring door differs in the box as well as the UVs. It hangs centred in its frame,
     * because that is what the shape looks like -- but a blockstate turns a model about the
     * centre of its block rather than about the hinge, so a centred box rotated 90° stays
     * centred and becomes a bar across the doorway, attached to nothing. Only the closed state
     * can afford to be centred.
     */
    public String modelStem(String material, String rowKind, String role, boolean swung) {
        String stem = modelStem(material, rowKind, role);
        return swung ? stem + "_open" : stem;
    }

    /**
     * The same, for a row whose head is arched.
     *
     * <p>Every row of a stone door has <b>three</b> models where other rows have two, and none
     * of them is another turned round. Its masonry -- the jambs, the sill, the lintel, the ring
     * over an arched head -- has to end up in the plane of the wall whichever way the leaf went,
     * so each swing needs the stone written turned the other way, and the two swings turn
     * opposite ways. Which of the two a part takes is decided by the end its leaf pivots about,
     * not by the hinge: on a door that opens from the middle each half pivots at a fixed end and
     * only one of the two ever occurs.
     */
    public String modelStem(String material, String rowKind, String role, boolean swung,
                            boolean pivotAtLowEnd) {
        String stem = modelStem(material, rowKind, role);
        if (!swung) {
            return stem;
        }
        if (this != STONE) {
            return stem + "_open";
        }
        return stem + (pivotAtLowEnd ? "_open_low" : "_open_high");
    }
}
