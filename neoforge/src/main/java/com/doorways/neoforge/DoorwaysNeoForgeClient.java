package com.doorways.neoforge;

import com.doorways.Doorways;
import com.doorways.block.DoorwaysContent;
import com.doorways.client.SlidingPanelsRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The NeoForge half of the renderer wiring.
 *
 * <p>The body lives in {@code common} and is shared; what differs is how each loader is told
 * about it. {@code Dist.CLIENT} on the annotation is what keeps this class off a dedicated
 * server, where the classes it names do not exist.
 */
@EventBusSubscriber(modid = Doorways.MOD_ID, value = Dist.CLIENT)
public final class DoorwaysNeoForgeClient {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                DoorwaysContent.slidingPanels(), context -> new SlidingPanelsRenderer());
    }

    private DoorwaysNeoForgeClient() {}
}
