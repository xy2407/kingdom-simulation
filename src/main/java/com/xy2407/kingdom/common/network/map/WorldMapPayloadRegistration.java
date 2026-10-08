package com.xy2407.kingdom.common.network.map;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 地图网络包注册：请求包由客户端上行，数据包由服务端下发。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class WorldMapPayloadRegistration {

    private WorldMapPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(WorldMapRequestPacket.TYPE,
                WorldMapRequestPacket.STREAM_CODEC, WorldMapRequestPacket::handle);
        registrar.playToClient(WorldMapDataPacket.TYPE,
                WorldMapDataPacket.STREAM_CODEC, WorldMapDataPacket::handle);
    }
}
