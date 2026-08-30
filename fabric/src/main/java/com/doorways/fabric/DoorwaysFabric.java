package com.doorways.fabric;

import com.doorways.Doorways;
import com.doorways.block.DoorStyle;
import com.doorways.block.DoorVariant;
import com.doorways.block.DoorwaysContent;
import java.util.Map;
import java.util.function.Supplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.registry.OxidizableBlocksRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopperCollection;

public final class DoorwaysFabric implements ModInitializer {

    /** The registered doors, keyed by variant. Filled in at start-up. */
    public static Map<DoorVariant, Supplier<Block>> doors = Map.of();

    @Override
    public void onInitialize() {
        // On Fabric the registries are still open at start-up, so the factory is called
        // immediately and the returned Supplier is a constant.
        DoorwaysContent.Registrar registrar = new DoorwaysContent.Registrar() {
            @Override
            public Supplier<Block> block(ResourceKey<Block> key, Supplier<Block> factory) {
                Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.get());
                return () -> block;
            }

            @Override
            public Supplier<Item> item(ResourceKey<Item> key, Supplier<Item> factory) {
                Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.get());
                return () -> item;
            }
        };

        doors = DoorwaysContent.registerAll(Doorways.MOD_ID, registrar);
        Supplier<Item> hinge = DoorwaysContent.registerHinge(Doorways.MOD_ID, registrar);
        registerCopperFamilies();

        // Own tab: with 168 doors, dumping them into a vanilla tab would make it unusable.
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.fromNamespaceAndPath(Doorways.MOD_ID, "doorways")),
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.doorways"))
                        .icon(() -> new ItemStack(hinge.get()))
                        .displayItems((parameters, output) -> {
                            output.accept(hinge.get());
                            doors.values().forEach(door -> output.accept(door.get()));
                        })
                        .build());

        Doorways.init();
    }

    /**
     * Wires the copper doors into the game's oxidation system.
     *
     * <p>One call per family (width × style) covers everything: oxidising over time, waxing
     * with honeycomb, removing wax with an axe, and scraping one stage back. Vanilla's maps are
     * static and closed to mods, so this registry is the only way in. NeoForge expresses the
     * same thing as two data map files and no code.
     */
    private static void registerCopperFamilies() {
        for (int width = 1; width <= 4; width++) {
            for (boolean glass : new boolean[] {false, true}) {
                OxidizableBlocksRegistry.registerWeatheringCopperBlocks(
                        new WeatheringCopperCollection<>(
                                byState("", width, glass),
                                byState("waxed_", width, glass)));
            }
        }
    }

    private static WeatheringCopperCollection.ByState<Block> byState(
            String prefix, int width, boolean glass) {
        return new WeatheringCopperCollection.ByState<>(
                copper(prefix + "copper", width, glass),
                copper(prefix + "exposed_copper", width, glass),
                copper(prefix + "weathered_copper", width, glass),
                copper(prefix + "oxidized_copper", width, glass));
    }

    private static Block copper(String material, int width, boolean glass) {
        return DoorVariant.find(material, width, glass ? DoorStyle.GLAZED : DoorStyle.SOLID)
                .map(doors::get)
                .map(Supplier::get)
                .orElseThrow(() -> new IllegalStateException(
                        "missing copper door: " + material + " " + width + " glass=" + glass));
    }
}
