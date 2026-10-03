package com.xy2407.kingdom.common.date;

import com.xy2407.kingdom.ModMain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 日期同步包的网络注册点
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class DatePayloadRegistration {
    private DatePayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(DateSyncPacket.TYPE, DateSyncPacket.STREAM_CODEC, DateSyncPacket::handle);
    }
}
