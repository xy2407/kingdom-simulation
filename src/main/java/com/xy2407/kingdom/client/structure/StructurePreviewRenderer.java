package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.ModMain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 建筑预览的每帧渲染钩子。
 *
 * <p>选择关卡渲染阶段而不是在页面绘制过程中嵌套渲染：这个阶段稳定在 GUI 之前，
 * 离屏纹理在页面取用时一定已经写好，同时完全不用把离屏渲染塞进 AUI 的绘制流程里
 * 去处理 GL 状态嵌套。</p>
 *
 * <p>代价是要求当前存在世界（在主菜单上不会触发），查看器因此需要在游戏内打开。</p>
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