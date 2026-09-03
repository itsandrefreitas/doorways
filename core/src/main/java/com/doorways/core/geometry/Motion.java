package com.doorways.core.geometry;

/**
 * How a leaf gets out of the way.
 *
 * <p>Independent of {@link DoorMode}, which says how many leaves there are. A door can have one
 * leaf or two, and separately from that it can turn about a hinge or run along the wall. Folding
 * the two into one enum would make "two leaves that slide" -- the four-wide sliding door --
 * impossible to express.
 */
public enum Motion {

    /** The leaf turns 90° about one of its ends, and may leave its frame doing so. */
    SWING,

    /**
     * The leaf runs along the wall and parks behind itself.
     *
     * <p>Panels neither shrink nor leave the door's own columns. A sliding leaf is two panels on
     * two tracks at different depths, and opening runs one behind the other, so the whole leaf
     * ends up in a single column and the rest are left clear. That is what a shoji does.
     *
     * <p>Two consequences fall out of it: a sliding door needs no space beside it, and it can
     * never be blocked -- there is nothing to validate, because nothing moves.
     */
    SLIDE
}
