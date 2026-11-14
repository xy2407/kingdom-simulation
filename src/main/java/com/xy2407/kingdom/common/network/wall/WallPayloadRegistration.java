package com.xy2407.kingdom.common.network.wall;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 城墙网络包注册点，写法沿用道路包注册
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class WallPayloadRegistration {

    private WallPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(WallSubmitPacket.TYPE,
                WallSubmitPacket.STREAM_CODEC, WallSubmitPacket::handle);
        registrar.playToServer(WallDeletePacket.TYPE,
                WallDeletePacket.STREAM_CODEC, WallDeletePacket::handle);
        registrar.playToServer(WallStateRequestPacket.TYPE,
                WallStateRequestPacket.STREAM_CODEC, WallStateRequestPacket::handle);
        registrar.playToServer(WallPickPacket.TYPE,
                WallPickPacket.STREAM_CODEC, WallPickPacket::handle);
        registrar.playToClient(WallStatePacket.TYPE,
                WallStatePacket.STREAM_CODEC, WallStatePacket::handle);
        registrar.playToClient(WallPickResultPacket.TYPE,
                WallPickResultPacket.STREAM_CODEC, WallPickResultPacket::handle);
    }
}
