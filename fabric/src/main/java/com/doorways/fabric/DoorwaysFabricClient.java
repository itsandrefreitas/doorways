package com.doorways.fabric;

import com.doorways.block.DoorwaysContent;
import com.doorways.client.SlidingPanelsRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * The mod's first client-side code, and the only thing on this side of the line.
 *
 * <p>Registration only, as with the GameTests: the renderer itself lives in {@code common} and
 * is written in plain vanilla API, so NeoForge registers the same class through its own event.
 * Nothing on a dedicated server ever loads it.
 *
 * <p>This calls vanilla's own registry rather than Fabric's helper, which is deprecated in
 * favour of exactly this: {@code BlockEntityRenderers.register} is private in vanilla, and the
 * transitive access wideners the mod already loads are what open it.
 */
public final class DoorwaysFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(
                DoorwaysContent.slidingPanels(), context -> new SlidingPanelsRenderer(context.sprites()));
    }
}
