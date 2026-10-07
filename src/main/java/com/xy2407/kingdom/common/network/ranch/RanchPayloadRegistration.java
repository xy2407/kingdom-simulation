package com.xy2407.kingdom.common.network.ranch;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 养殖网络包注册：请求、操作、状态三个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RanchPayloadRegistration {

    private RanchPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RanchPanelRequestPacket.TYPE,
                RanchPanelRequestPacket.STREAM_CODEC, RanchPanelRequestPacket::handle);
        registrar.playToServer(RanchPanelActionPacket.TYPE,
                RanchPanelActionPacket.STREAM_CODEC, RanchPanelActionPacket::handle);
        registrar.playToClient(RanchPanelStatePacket.TYPE,
                RanchPanelStatePacket.STREAM_CODEC, RanchPanelStatePacket::handle);
    }
}
