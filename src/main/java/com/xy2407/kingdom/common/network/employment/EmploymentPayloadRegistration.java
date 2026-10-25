package com.xy2407.kingdom.common.network.employment;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 雇佣网络包注册：打开请求、提交、状态三个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class EmploymentPayloadRegistration {

    private EmploymentPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(EmploymentOpenRequestPacket.TYPE,
                EmploymentOpenRequestPacket.STREAM_CODEC, EmploymentOpenRequestPacket::handle);
        registrar.playToServer(EmploymentSubmitPacket.TYPE,
                EmploymentSubmitPacket.STREAM_CODEC, EmploymentSubmitPacket::handle);
        registrar.playToServer(EmploymentDismissPacket.TYPE,
                EmploymentDismissPacket.STREAM_CODEC, EmploymentDismissPacket::handle);
        registrar.playToClient(EmploymentPanelStatePacket.TYPE,
                EmploymentPanelStatePacket.STREAM_CODEC, EmploymentPanelStatePacket::handle);
    }
}
