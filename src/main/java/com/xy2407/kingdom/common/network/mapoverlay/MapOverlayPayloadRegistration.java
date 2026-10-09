package com.xy2407.kingdom.common.network.mapoverlay;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 地图叠层网络包注册：请求包由客户端上行，数据包由服务端下发。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class MapOverlayPayloadRegistration {

    private MapOverlayPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(MapOverlayRequestPacket.TYPE,
                MapOverlayRequestPacket.STREAM_CODEC, MapOverlayRequestPacket::handle);
        registrar.playToClient(MapOverlayDataPacket.TYPE,
                MapOverlayDataPacket.STREAM_CODEC, MapOverlayDataPacket::handle);
    }
}
