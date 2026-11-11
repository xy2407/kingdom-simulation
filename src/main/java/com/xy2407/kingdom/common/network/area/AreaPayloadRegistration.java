package com.xy2407.kingdom.common.network.area;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 作业范围网络包注册点（伐木/养殖共用）。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class AreaPayloadRegistration {

    private AreaPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(AreaSubmitPacket.TYPE,
                AreaSubmitPacket.STREAM_CODEC, AreaSubmitPacket::handle);
        registrar.playToServer(AreaStateRequestPacket.TYPE,
                AreaStateRequestPacket.STREAM_CODEC, AreaStateRequestPacket::handle);
        registrar.playToClient(AreaStatePacket.TYPE,
                AreaStatePacket.STREAM_CODEC, AreaStatePacket::handle);
        registrar.playToServer(AreaOverviewRequestPacket.TYPE,
                AreaOverviewRequestPacket.STREAM_CODEC, AreaOverviewRequestPacket::handle);
        registrar.playToClient(AreaOverviewPacket.TYPE,
                AreaOverviewPacket.STREAM_CODEC, AreaOverviewPacket::handle);
    }
}
