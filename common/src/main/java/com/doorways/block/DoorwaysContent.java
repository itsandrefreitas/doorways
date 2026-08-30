package com.doorways.block;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Creates and registers the doors.
 *
 * <p>The loop lives here; each loader supplies only its {@link Registrar}. Fabric registers
 * directly at start-up, NeoForge requires deferred registration.
 */
public final class DoorwaysContent {

    /** A component of every door recipe. Reusable by v0.2's sliding doors. */
    public static final String HINGE = "iron_hinge";

    /**
     * The one thing the two loaders do differently.
     *
     * <p>A <b>factory</b> is passed, not an instance: on NeoForge the block registry is already
     * frozen by the time mods construct, and {@code new Block(...)} blows up with
     * {@code Registry is already frozen}. The loader calls the factory when it suits --
     * immediately on Fabric, during the deferred registration phase on NeoForge.
     *
     * <p>It returns a {@link Supplier} for the same reason: the caller cannot assume the object
     * already exists.
     */
    public interface Registrar {
        Supplier<Block> block(ResourceKey<Block> key, Supplier<Block> factory);

        Supplier<Item> item(ResourceKey<Item> key, Supplier<Item> factory);
    }

    /** Registers the hinge item. */
    public static Supplier<Item> registerHinge(String modId, Registrar registrar) {
        ResourceKey<Item> key = ResourceKey.create(
                Registries.ITEM, Identifier.fromNamespaceAndPath(modId, HINGE));
        return registrar.item(key, () -> new Item(new Item.Properties().setId(key)));
    }

    /**
     * Registers everything and returns the doors, keyed by variant.
     *
     * <p>Doors use {@link DoubleHighBlockItem}, the same item vanilla doors use, which already
     * handles clearing the position above before placing. The remaining columns are placed by
     * the block's {@code setPlacedBy}.
     */
    public static Map<DoorVariant, Supplier<Block>> registerAll(String modId, Registrar registrar) {
        Map<DoorVariant, Supplier<Block>> doors = new LinkedHashMap<>();

        for (DoorVariant variant : DoorVariant.ALL) {
            ResourceKey<Block> blockKey = variant.blockKey(modId);
            ResourceKey<Item> itemKey = variant.itemKey(modId);

            Supplier<Block> block = registrar.block(blockKey, () -> variant.createBlock(modId));

            registrar.item(itemKey, () -> {
                Block door = block.get();
                Item.Properties properties = new Item.Properties()
                        .useBlockDescriptionPrefix()
                        .requiredFeatures(door.requiredFeatures())
                        .setId(itemKey);
                DoubleHighBlockItem item = new DoubleHighBlockItem(door, properties);
                // Without this the game does not know which item matches this block, which
                // breaks pick-block and drops. It is what vanilla's Items.registerItem does.
                item.registerBlocks(Item.BY_BLOCK, item);
                return item;
            });

            doors.put(variant, block);
        }

        return Map.copyOf(doors);
    }

    private DoorwaysContent() {}
}
