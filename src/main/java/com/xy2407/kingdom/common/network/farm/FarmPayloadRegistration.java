package com.xy2407.kingdom.common.network.farm;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 农业网络包注册：请求、操作、状态三个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class FarmPayloadRegistration {

    private FarmPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(FarmPanelRequestPacket.TYPE,
                FarmPanelRequestPacket.STREAM_CODEC, FarmPanelRequestPacket::handle);
        registrar.playToServer(FarmPanelActionPacket.TYPE,
                FarmPanelActionPacket.STREAM_CODEC, FarmPanelActionPacket::handle);
        registrar.playToClient(FarmPanelStatePacket.TYPE,
                FarmPanelStatePacket.STREAM_CODEC, FarmPanelStatePacket::handle);
    }
}
