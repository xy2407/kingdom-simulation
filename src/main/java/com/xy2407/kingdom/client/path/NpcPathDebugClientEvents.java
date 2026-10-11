package com.xy2407.kingdom.client.path;

import com.xy2407.kingdom.ModMain;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * NPC 寻路调试线框在世界层的渲染入口，挂在实体渲染之后
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class NpcPathDebugClientEvents {

    private NpcPathDebugClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        NpcPathDebugRenderer.onRenderLevel(event.getPoseStack(), event.getCamera());
    }
}
