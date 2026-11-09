package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.ModMain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 建筑预览的每帧渲染钩子，在关卡渲染阶段驱动离屏预览重绘。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructurePreviewRenderer {

    private StructurePreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // 卡片网格改由 StructurePreviewElement 绘制时驱动（那边一定在渲染线程上），
        // 这里不再重复驱动
        StructureDebugChat.once("hook-fire", "渲染钩子已触发：stage=%s", event.getStage());
        if (Minecraft.getInstance().level == null) {
            StructureDebugChat.once("hook-nolevel", "渲染钩子跳过：当前没有世界");
            return;
        }
        if (!StructurePreviewTarget.isAlive()) {
            StructureDebugChat.once("hook-dead", "渲染钩子跳过：预览元素未在绘制（isAlive=false）");
            return;
        }
        if (StructurePreviewTarget.isVanillaScreenActive()) {
            StructureDebugChat.once("hook-vanilla", "渲染钩子跳过：原版调试屏正在接管渲染");
            return;
        }
        StructurePreviewTarget.renderFrame();
    }
}
