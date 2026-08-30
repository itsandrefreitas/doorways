package com.doorways.fabric.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Datagen entrypoint.
 *
 * <p>For now it generates only the blockstates, which is where the real problem was: they were
 * the one thing in {@code tools/gen_assets.py} that <b>reimplemented logic</b> rather than
 * repeating data. The rest of that script -- textures, models, recipes, loot, lang -- is
 * mechanical, and the textures can never come from here: datagen emits JSON, not PNG.
 *
 * <p>The two generators coexist because they write disjoint files, and the output targets
 * {@code common}'s resources, which both loaders share. JSON has no side.
 */
public class DoorwaysDataGen implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider(DoorwayBlockStateProvider::new);
    }
}
