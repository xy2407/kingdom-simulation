package com.xy2407.kingdom.client.faction;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.common.network.rts.RtsClientHooks;

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
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/**
 * 择旗界面的预览入口，登记键位并在客户端设置阶段安装选中回执
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class FactionSelectClientEvents {

    public static final KeyMapping PREVIEW_FACTION_SELECT = new KeyMapping(
            "key.xy2407_kingdom.faction_select_preview",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.xy2407_kingdom"
    );

    private FactionSelectClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PREVIEW_FACTION_SELECT);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        RtsClientHooks.selectionSink = RtsModeManager::applyServerSelection;
        /* 把阵营状态包接到分发器上 */
        FactionClientState.install();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (PREVIEW_FACTION_SELECT.consumeClick()) {
            if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
                continue;
            }
            /* 手动重开择旗界面。传 null 表示"走真流程"——结果由
               FactionClientState 收状态包后处理，不再是一条预览聊天。 */
            FactionSelectPage.open(null);
        }
    }
}
