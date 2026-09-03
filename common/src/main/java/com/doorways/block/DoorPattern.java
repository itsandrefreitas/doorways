package com.doorways.block;

import org.jspecify.annotations.Nullable;

/**
 * A painting a sliding door can carry.
 *
 * <p>Fusuma are painted, and always have been: the panel is the canvas of the room. This is the
 * list of what can be painted on one.
 *
 * <h2>Why this is not a blockstate property</h2>
 * Because it would cost more than everything the mod has saved. The 24 fusuma carry 9,216
 * blockstates between them, and a property multiplies that by its number of values -- nine
 * paintings would be 82,944, three times the whole mod (D-38). The painting therefore lives in
 * the block entity, which the sliding doors already have and which costs nothing per door that
 * has no painting on it.
 *
 * <p>That has a consequence worth naming: the block entity used to hold nothing the game
 * depended on, and now it holds something a player made. It is saved and synchronised, and it is
 * the first thing in this mod that a player can lose.
 */
public enum DoorPattern {

    /** An evergreen with the maple's shape, under a moon. */
    PINE("pine"),

    /** Three culms at three depths, a rock, and birds. */
    BAMBOO("bamboo"),

    /** A branch in flower, and petals already falling. */
    CHERRY("cherry"),

    /** Maple gone over to red, with leaves along the floor. */
    AUTUMN("autumn"),

    /** The moment a wave curls, and the foam coming off the lip. */
    WAVE("wave"),

    /** A fall between two cliffs, drawn by what is left unpainted. */
    WATERFALL("waterfall"),

    /** Ridges one behind another, each fainter, with snow left as paper. */
    MOUNTAIN("mountain"),

    /** A harvest moon over reeds and water. The one painted in colour. */
    MOON("moon"),

    /** Carp, seen from above. This panel was a crane twice and could not be read as one. */
    KOI("koi");

    private final String id;

    DoorPattern(String id) {
        this.id = id;
    }

    /** The name used in the block entity's data and in the texture path. */
    public String id() {
        return id;
    }

    /** The registry name of the item that paints this. */
    public String itemName() {
        return "fusuma_" + id;
    }

    /** The pattern with this id, or null -- which is what an unknown name from disk becomes. */
    public static @Nullable DoorPattern byId(String id) {
        for (DoorPattern pattern : values()) {
            if (pattern.id.equals(id)) {
                return pattern;
            }
        }
        return null;
    }
}
