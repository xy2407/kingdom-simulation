package com.xy2407.kingdom.client.aui;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.structure.StructureDebugChat;
import com.sighs.apricityui.registry.ApricityUIRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 向晴雪UI注册本模组的扩展元素
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class KingdomAuiIntegration {

    public static final String AUI_ELEMENT_PACKAGE = "com.xy2407.kingdom.client.aui";

    private KingdomAuiIntegration() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ApricityUIRegistry.scanPackage(AUI_ELEMENT_PACKAGE);
            ApricityUIRegistry.register();
            StructureDebugChat.chat("AUI 扩展元素注册完成：扫描包=%s（structure-preview 标签应已生效）",
                    AUI_ELEMENT_PACKAGE);
        });
    }
}
