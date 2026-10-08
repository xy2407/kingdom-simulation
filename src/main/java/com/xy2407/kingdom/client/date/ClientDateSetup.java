package com.xy2407.kingdom.client.date;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.date.DateSyncBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端安装日期同步回调，把服务端天数转交给本地缓存。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class ClientDateSetup {
    private ClientDateSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        DateSyncBridge.install(ClientDateInfo::setTotalDay);
    }
}
