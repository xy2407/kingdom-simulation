package com.xy2407.kingdom.common.network.military;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 军事建筑面板的网络包注册，绑定征召动作上报与面板状态下发
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class MilitaryPayloadRegistration {

    private MilitaryPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(MilitaryPanelActionPacket.TYPE,
                MilitaryPanelActionPacket.STREAM_CODEC, MilitaryPanelActionPacket::handle);
        registrar.playToClient(MilitaryPanelStatePacket.TYPE,
                MilitaryPanelStatePacket.STREAM_CODEC, MilitaryPanelStatePacket::handle);
    }
}
