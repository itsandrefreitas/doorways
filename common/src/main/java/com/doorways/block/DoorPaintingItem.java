package com.doorways.block;

import net.minecraft.world.item.Item;

/**
 * A painting, in the hand rather than on a door.
 *
 * <p>It carries which painting it is, so that the door it is used on can ask the item instead of
 * looking a registry name up in a map. One item per pattern: a single item with the pattern in a
 * data component would be tidier in the registry and worse in the inventory, where nine paintings
 * would look like nine copies of the same thing.
 */
public class DoorPaintingItem extends Item {

    private final DoorPattern pattern;

    public DoorPaintingItem(DoorPattern pattern, Item.Properties properties) {
        super(properties);
        this.pattern = pattern;
    }

    public DoorPattern pattern() {
        return pattern;
    }
}
