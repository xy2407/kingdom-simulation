package com.xy2407.kingdom.common.network.mining;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 采矿网络包注册点，包含面板快照与笔刷提交两组。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class MiningPayloadRegistration {

    private MiningPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(MiningAreaSubmitPacket.TYPE,
                MiningAreaSubmitPacket.STREAM_CODEC, MiningAreaSubmitPacket::handle);
        registrar.playToServer(MiningPanelRequestPacket.TYPE,
                MiningPanelRequestPacket.STREAM_CODEC, MiningPanelRequestPacket::handle);
        registrar.playToServer(MiningPanelActionPacket.TYPE,
                MiningPanelActionPacket.STREAM_CODEC, MiningPanelActionPacket::handle);
        registrar.playToClient(MiningPanelStatePacket.TYPE,
                MiningPanelStatePacket.STREAM_CODEC, MiningPanelStatePacket::handle);
    }
}
