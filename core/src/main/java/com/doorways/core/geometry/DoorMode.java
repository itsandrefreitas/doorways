package com.doorways.core.geometry;

/** How a door divides into leaves. See DECISIONS.md, D-05. */
public enum DoorMode {
    /** A single rigid leaf, pivoting about one of its ends. */
    SINGLE,
    /** Two symmetric leaves opening from the centre. Requires an even width. */
    SPLIT;

    /** The v0.1 defaults, matching the table in §2 of the specification. */
    public static DoorMode defaultFor(int width) {
        return width % 2 == 0 ? SPLIT : SINGLE;
    }
}
