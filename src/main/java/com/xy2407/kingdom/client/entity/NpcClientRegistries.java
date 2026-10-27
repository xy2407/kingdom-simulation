package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染器注册：登记市民与 RTS 假人两种实体的渲染器。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class NpcClientRegistries {

    private NpcClientRegistries() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.NPC.get(), NpcEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.RTS_FAKE_PLAYER.get(), RtsFakePlayerRenderer::new);
        event.registerEntityRenderer(ModEntities.SEAT.get(), SeatRenderer::new);
    }
}
