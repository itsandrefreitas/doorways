package com.doorways.core.geometry;

/**
 * Which end a single leaf hinges on.
 *
 * <p>Relative to the axis {@code R = FACING.clockwise()}, along which the {@code PART} index
 * grows. {@link #LEFT} puts the hinge at {@code PART 0}; {@link #RIGHT} at
 * {@code PART width-1}.
 *
 * <p>Ignored under {@link DoorMode#SPLIT} — in that mode each half hinges implicitly at its
 * outer end (see DECISIONS.md, D-05).
 */
public enum Hinge {
    LEFT,
    RIGHT
}
