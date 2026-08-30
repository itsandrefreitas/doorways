package com.doorways.neoforge;

import com.doorways.Doorways;
import com.doorways.block.DoorStyle;
import com.doorways.block.DoorVariant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

/**
 * Confirms the copper doors are wired into NeoForge's data maps.
 *
 * <p>A broken link here fails silently: the doors register, the mod loads and the data maps
 * load, but waxing and oxidation do nothing. Nothing reports an error, and the only symptom is
 * in game. This check turns that into a log line at load time.
 *
 * <p>Data maps exist only on NeoForge, so no Fabric test can cover this. That is why the check
 * lives in this module rather than in {@code common}.
 *
 * <p>It runs on every data map load -- on the server when reading datapacks, on the client when
 * receiving them synced -- so it also catches a desync between the two.
 */
public final class CopperDataMapCheck {

    /** The oxidation chain, from fresh copper to fully oxidised. */
    private static final List<String> STATES =
            List.of("copper", "exposed_copper", "weathered_copper", "oxidized_copper");

    public static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        event.ifRegistry(Registries.BLOCK, registry -> verify());
    }

    private static void verify() {
        List<String> broken = new ArrayList<>();

        for (int width = 1; width <= 4; width++) {
            for (boolean glass : new boolean[] {false, true}) {
                for (int i = 0; i < STATES.size(); i++) {
                    String state = STATES.get(i);
                    Block bare = door(state, width, glass);
                    Block waxed = door("waxed_" + state, width, glass);

                    // Waxing with honeycomb, and removing the wax with an axe.
                    expect(broken, "waxables", bare, DataMapHooks.getBlockWaxed(bare), waxed);
                    expect(broken, "waxables (inverse)", waxed,
                            DataMapHooks.getBlockUnwaxed(waxed), bare);

                    // Oxidising over time. The last stage has no successor.
                    if (i + 1 < STATES.size()) {
                        Block next = door(STATES.get(i + 1), width, glass);
                        expect(broken, "oxidizables", bare,
                                DataMapHooks.getNextOxidizedStage(bare), next);
                    }
                }
            }
        }

        if (broken.isEmpty()) {
            return;
        }

        // Logs only, never throws, not even in development.
        //
        // An exception here propagates up through datapack loading, which prevents opening any
        // world, prevents Safe Mode -- the mod is still loaded -- and hangs world creation. A
        // door that will not wax must not make the game unreachable.
        Doorways.LOGGER.error("Copper data maps incomplete: {} links missing or wrong.",
                broken.size());
        broken.forEach(line -> Doorways.LOGGER.error("  {}", line));
    }

    private static void expect(List<String> broken, String map, Block from,
                               Block actual, Block expected) {
        if (actual == expected) {
            return;
        }
        broken.add(map + ": " + name(from) + " -> " + name(actual)
                + " (expected " + name(expected) + ")");
    }

    private static Block door(String material, int width, boolean glass) {
        DoorStyle style = glass ? DoorStyle.GLAZED : DoorStyle.SOLID;
        DoorVariant variant = DoorVariant.find(material, width, style)
                .orElseThrow(() -> new IllegalStateException(
                        "no such variant: " + material + " " + width + " glass=" + glass));
        return BuiltInRegistries.BLOCK.getValue(variant.blockKey(Doorways.MOD_ID));
    }

    private static String name(Block block) {
        return block == null ? "nothing" : BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private CopperDataMapCheck() {}
}
