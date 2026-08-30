package com.doorways.block;

import com.doorways.core.geometry.Swing;
import net.minecraft.util.StringRepresentable;

/**
 * The blockstate face of {@link Swing}.
 *
 * <p>{@code core} owns the geometry and imports nothing from Minecraft, so it cannot implement
 * {@link StringRepresentable} -- which is exactly what {@code EnumProperty} demands. This is the
 * same split {@code Facing} and {@code Hinge} already live under, with one difference: those two
 * had vanilla counterparts to convert to, and a swing has none, so the counterpart is written
 * here. {@code WideDoorGeometry} converts between the two, as it does for the others.
 *
 * <p>The names below appear in all 200 blockstate files and in every saved world, so they are
 * fixed once anyone has placed a door.
 */
public enum DoorSwing implements StringRepresentable {

    CLOSED("closed"),
    OUT("out"),
    BACK("back");

    private final String name;

    DoorSwing(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
