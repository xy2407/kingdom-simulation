package com.xy2407.kingdom.common.network.forge;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 锻造坊面板的网络包注册，绑定打开请求、提交动作与状态下发
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ForgePayloadRegistration {

    private ForgePayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ForgePanelRequestPacket.TYPE,
                ForgePanelRequestPacket.STREAM_CODEC, ForgePanelRequestPacket::handle);
        registrar.playToServer(ForgePanelActionPacket.TYPE,
                ForgePanelActionPacket.STREAM_CODEC, ForgePanelActionPacket::handle);
        registrar.playToClient(ForgePanelStatePacket.TYPE,
                ForgePanelStatePacket.STREAM_CODEC, ForgePanelStatePacket::handle);
    }
}
