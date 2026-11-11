package com.xy2407.kingdom.common.network.industry;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 工业建筑面板的网络包注册，绑定请求、操作与用工状态的收发方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class IndustryPayloadRegistration {

    private IndustryPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(IndustryPanelRequestPacket.TYPE,
                IndustryPanelRequestPacket.STREAM_CODEC, IndustryPanelRequestPacket::handle);
        registrar.playToServer(IndustryPanelActionPacket.TYPE,
                IndustryPanelActionPacket.STREAM_CODEC, IndustryPanelActionPacket::handle);
        registrar.playToClient(IndustryPanelStatePacket.TYPE,
                IndustryPanelStatePacket.STREAM_CODEC, IndustryPanelStatePacket::handle);
        registrar.playToServer(IndustryStaffActionPacket.TYPE,
                IndustryStaffActionPacket.STREAM_CODEC, IndustryStaffActionPacket::handle);
        registrar.playToClient(IndustryStaffStatePacket.TYPE,
                IndustryStaffStatePacket.STREAM_CODEC, IndustryStaffStatePacket::handle);
    }
}
