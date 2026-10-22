package com.xy2407.kingdom.client.map;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

import org.lwjgl.glfw.GLFW;

/**
 * 世界地图键位：M 键全屏打开；RTS 模式下让位给采矿画笔不响应。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class WorldMapKeybind {

    public static final KeyMapping OPEN_WORLD_MAP = new KeyMapping(
            "key.xy2407_kingdom.world_map",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.xy2407_kingdom"
    );

    private WorldMapKeybind() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_WORLD_MAP);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_WORLD_MAP.consumeClick()) {
            if (RtsModeManager.isActive()) {
                break;
            }
            if (Minecraft.getInstance().screen == null) {
                WorldMapScreen.open();
            }
        }
    }
}
