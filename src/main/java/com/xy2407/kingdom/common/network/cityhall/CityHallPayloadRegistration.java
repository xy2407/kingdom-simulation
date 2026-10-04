package com.xy2407.kingdom.common.network.cityhall;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 市政厅网络包注册
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class CityHallPayloadRegistration {

    private CityHallPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(CityHallOpenPacket.TYPE,
                CityHallOpenPacket.STREAM_CODEC, CityHallOpenPacket::handle);
        registrar.playToClient(CityHallDataPacket.TYPE,
                CityHallDataPacket.STREAM_CODEC, CityHallDataPacket::handle);
    }
}
