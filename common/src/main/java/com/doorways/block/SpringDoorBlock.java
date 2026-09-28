package com.doorways.block;

import com.doorways.core.geometry.DoorMode;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A door on a double-acting spring hinge, which can be open on either side of its frame.
 *
 * <p>It exists as its own class for one value. {@link DoorSwing#BACK} is unreachable on a door
 * with an ordinary hinge -- there is no second side for it to be open on -- and declaring it on
 * the shared class declared it on every door: 29,184 blockstates across the 202 that could never
 * hold it, so that 24 could.
 *
 * <p>Everything else about a spring door already lived in {@link DoorStyle#springLoaded()}: it
 * swings both ways, shuts by itself, and ignores redstone. Those are behaviour and stay there.
 * What is here is the one thing that has to be in the state, and therefore in the class.
 */
public class SpringDoorBlock extends WideDoorBlock {

    /** The same property as the shared one, with the third position a spring hinge allows. */
    public static final EnumProperty<DoorSwing> SWING_BOTH_WAYS =
            EnumProperty.create("swing", DoorSwing.class);

    public SpringDoorBlock(int width, DoorMode mode, DoorStyle style, BlockSetType type,
                           BlockBehaviour.Properties properties) {
        super(width, mode, style, type, properties);
    }

    @Override
    public EnumProperty<DoorSwing> swingProperty() {
        return SWING_BOTH_WAYS;
    }

    /**
     * A spring door records no signal, because it cannot be held open by one.
     *
     * <p>It already ignores redstone entirely -- a latchless hinge has no position to be held
     * in -- so {@code POWERED} was a boolean written at placement and read by nobody. Dropping
     * it halves the states of all 24 spring doors: 3,456 of them (D-38).
     */
    @Override
    public boolean recordsSignal() {
        return false;
    }
}
