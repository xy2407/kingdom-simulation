package com.xy2407.kingdom.client.rts;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xy2407.kingdom.ModMain;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import org.lwjgl.opengl.GL11;

/**
 * RTS 的客户端注册入口，登记键位与 HUD 图层（MOD 总线）
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class RtsClientSetup {

    private RtsClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RtsClientEvents.TOGGLE_RTS_MODE);
        event.register(RtsClientEvents.TOGGLE_RTS_ORTHO);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, RtsHudLayer.ID),
                RtsHudLayer.INSTANCE);


        /* ---- 聊天框不再参与深度缓冲 ----
         *
         * 现象：聊天文字压在 AUI 覆盖层文档（structures/viewer.html 这类 HUD）上面。
         *
         * 成因：原版聊天先画、先把自己的深度写进深度缓冲；而 AUI 的覆盖层文档落在
         * z≈1 的平面、且绘制时深度测试全程开着（Base#drawOverlayDocument）。
         * 两处位置重叠时，先写下的那份深度把后来的文档面整块裁掉。
         *
         * 治法：**让聊天不写深度**。它本来就靠 GUI 图层顺序定位（先背景、再 HUD、
         * 最后聊天），根本不需要深度缓冲来分前后。它一旦不写深度，缓冲里就没有它的
         * 记录，后面画的任何东西都不会被它裁——不依赖任何"谁先谁后 / z 谁大谁小"的假设。
         *
         * 影响面：只包住聊天这一层，进出一对、按进入前的状态原样还原，
         * 不把整个 GUI 帧的深度状态改坏。不动 AUI 的渲染归属，也不碰事件分发路径。
         */
        event.wrapLayer(VanillaGuiLayers.CHAT, layer -> (guiGraphics, deltaTracker) -> {
            /* 先冲一次：前面图层攒下的几何要在改状态之前落到缓冲里 */
            guiGraphics.flush();
            boolean depthTestWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
            boolean depthMaskWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            try {
                layer.render(guiGraphics, deltaTracker);
            } finally {
                /* 聊天的几何必须在放开写入之前提交，否则会带着新状态冲出去 */
                guiGraphics.flush();
                RenderSystem.depthMask(depthMaskWasOn);
                if (depthTestWasOn) {
                    RenderSystem.enableDepthTest();
                } else {
                    RenderSystem.disableDepthTest();
                }
            }
        });

        event.wrapLayer(VanillaGuiLayers.HOTBAR, layer -> (guiGraphics, deltaTracker) -> {
            if (!RtsModeManager.isActive()) {
                layer.render(guiGraphics, deltaTracker);
            }
        });
    }
}
