package com.doorways.block;

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
     * <p>It opens to one side like every other door here: swinging both ways would mean
     * recording which way it is open, and that contradicts D-04.
     */
    SALOON("_saloon", true),

    /** A wall of books. One door per width. */
    BOOKSHELF("", false);

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

    /** Whether a door of this style exists at the given width. */
    public boolean allowsWidth(int width) {
        // A saloon door is two swinging leaves. At odd widths one leaf would be wider than the
        // other, which is not what the shape is.
        return this != SALOON || width % 2 == 0;
    }

    /**
     * The id stem: {@code oak_saloon_doorway_2}, {@code glass_doorway_1}.
     *
     * <p>Styles that are not per-material drop the material from the name entirely -- the
     * material of a glass door is glass, and repeating it reads badly.
     */
    public String name(String material, int width) {
        return material + infix + "_doorway_" + width;
    }

    /**
     * The model stem for one half of one column.
     *
     * <p>{@link #GLAZED} is the exception: only its upper half differs from {@link #SOLID}, so
     * the lower half reuses the solid model rather than duplicating it per material.
     */
    public String modelStem(String material, boolean upper, String role) {
        String styled = this == GLAZED && !upper ? "" : infix;
        return material + styled + "_doorway_" + (upper ? "top" : "bottom") + "_" + role;
    }
}
