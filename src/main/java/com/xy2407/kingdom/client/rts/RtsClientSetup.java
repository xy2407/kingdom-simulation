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
 * RTS 的客户端注册入口，登记键位与 HUD 图层（MOD 总线）。
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

        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, RoadHudLayer.ID),
                RoadHudLayer.INSTANCE);

        event.wrapLayer(VanillaGuiLayers.CHAT, layer -> (guiGraphics, deltaTracker) -> {
            guiGraphics.flush();
            boolean depthTestWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
            boolean depthMaskWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            try {
                layer.render(guiGraphics, deltaTracker);
            } finally {
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
