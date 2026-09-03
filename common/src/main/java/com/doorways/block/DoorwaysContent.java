package com.doorways.block;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Creates and registers the doors.
 *
 * <p>The loop lives here; each loader supplies only its {@link Registrar}. Fabric registers
 * directly at start-up, NeoForge requires deferred registration.
 */
public final class DoorwaysContent {

    /** A component of every door recipe, except the sliding ones, which have no hinge. */
    public static final String HINGE = "iron_hinge";

    /** What a hinge is to the doors that swing. A fusuma runs in a groove, not on a pivot. */
    public static final String TRACK = "sliding_track";

    /** The block entity every sliding door carries, so that its panels can be drawn moving. */
    public static final String SLIDING_PANELS = "sliding_panels";

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

        /**
         * Typed to the one block entity the mod has rather than made generic.
         *
         * <p>A generic version would have to fight the wildcards on the registry for no gain
         * today. When a second one arrives -- a gate, say -- that is the moment to widen it.
         */
        Supplier<BlockEntityType<SlidingPanelsBlockEntity>> blockEntity(
                ResourceKey<BlockEntityType<?>> key,
                Supplier<BlockEntityType<SlidingPanelsBlockEntity>> factory);
    }

    private static Supplier<BlockEntityType<SlidingPanelsBlockEntity>> slidingPanels = () -> null;

    /** The block entity type sliding doors are drawn from. Valid after {@link #registerAll}. */
    public static BlockEntityType<SlidingPanelsBlockEntity> slidingPanels() {
        return slidingPanels.get();
    }

    /**
     * Registers one of the parts a door is built from -- {@link #HINGE} or {@link #TRACK}.
     *
     * <p>Which one a door needs says something about it: everything that swings starts from a
     * hinge, and everything that slides starts from a track.
     */
    public static Supplier<Item> registerComponent(String modId, Registrar registrar, String name) {
        ResourceKey<Item> key = ResourceKey.create(
                Registries.ITEM, Identifier.fromNamespaceAndPath(modId, name));
        return registrar.item(key, () -> new Item(new Item.Properties().setId(key)));
    }

    private static final Map<DoorPattern, Supplier<Item>> PAINTINGS =
            new EnumMap<>(DoorPattern.class);

    /**
     * Registers every painting there is.
     *
     * <p>The suppliers are kept because the door has to hand a painting back when one is brushed
     * off, and looking an item up by name at that moment would be a registry search for something
     * we already had.
     */
    public static void registerPaintings(String modId, Registrar registrar) {
        for (DoorPattern pattern : DoorPattern.values()) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath(modId, pattern.itemName()));
            PAINTINGS.put(pattern, registrar.item(key,
                    () -> new DoorPaintingItem(pattern, new Item.Properties().setId(key))));
        }
    }

    /** The item that paints this pattern. Valid after {@link #registerPaintings}. */
    public static Item painting(DoorPattern pattern) {
        Supplier<Item> item = PAINTINGS.get(pattern);
        return item == null ? Items.AIR : item.get();
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

        Map<DoorVariant, Supplier<Block>> registered = Map.copyOf(doors);

        // The set of blocks is resolved inside the factory, not here. On NeoForge the blocks do
        // not exist yet at this point (D-32); by the time the block entity registry is filled
        // they do, because vanilla processes BLOCK before BLOCK_ENTITY_TYPE.
        ResourceKey<BlockEntityType<?>> key = ResourceKey.create(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(modId, SLIDING_PANELS));
        slidingPanels = registrar.blockEntity(key, () -> {
            Set<Block> sliding = new LinkedHashSet<>();
            registered.forEach((variant, block) -> {
                if (variant.style().slides()) {
                    sliding.add(block.get());
                }
            });
            return new BlockEntityType<>(SlidingPanelsBlockEntity::new, sliding);
        });

        return registered;
    }

    private DoorwaysContent() {}
}
