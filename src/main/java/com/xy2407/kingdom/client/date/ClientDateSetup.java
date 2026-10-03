package com.xy2407.kingdom.client.date;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.date.DateSyncBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端安装日期同步回调
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class ClientDateSetup {
    private ClientDateSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        DateSyncBridge.install(ClientDateInfo::setTotalDay);
    }
}
