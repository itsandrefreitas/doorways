package com.doorways.core.geometry;

/**
 * Where a leaf sits: in its frame, or swung to one side of it.
 *
 * <p>Three states, not a boolean plus a direction. A closed leaf has no side, so recording
 * which way it last swung would be state nobody reads -- and it would double the closed
 * entries in all 200 blockstate files to say nothing.
 */
public enum Swing {

    /** In the frame, on the wall line. */
    CLOSED,

    /**
     * Swung towards {@code +FACING}, away from whoever placed the door (DECISIONS.md, D-04).
     *
     * <p>The only open state a one-way door can reach.
     */
    OUT,

    /**
     * Swung towards {@code -FACING}, back over whoever placed the door.
     *
     * <p>Only styles with a two-way hinge reach this.
     */
    BACK;

    public boolean isOpen() {
        return this != CLOSED;
    }
}
