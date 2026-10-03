package com.xy2407.kingdom.client.db;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.db.DbViewerHooks;
import com.xy2407.kingdom.common.network.db.DbViewerRequestPacket;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 数据库查看器客户端接线，注册波浪键并请求表快照
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class DbClientSetup {
    public static final KeyMapping OPEN_DB_VIEWER = new KeyMapping(
            "key.xy2407_kingdom.db_viewer", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.xy2407_kingdom");

    private DbClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        DbViewerHooks.snapshotSink = DbViewerScreen::accept;
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_DB_VIEWER);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_DB_VIEWER.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.screen instanceof DbViewerScreen)) {
                minecraft.setScreen(new DbViewerScreen());
            }
        }
    }

    public static void request(String table) {
        PacketDistributor.sendToServer(new DbViewerRequestPacket(table));
    }
}
