package com.xy2407.kingdom.common.network.road;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 道路网络包的注册点，写法沿用 NpcBackpackPayloadRegistration。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RoadPayloadRegistration {

    private RoadPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RoadTerritorySubmitPacket.TYPE,
                RoadTerritorySubmitPacket.STREAM_CODEC, RoadTerritorySubmitPacket::handle);
        registrar.playToServer(RoadLoadedChunksRequestPacket.TYPE,
                RoadLoadedChunksRequestPacket.STREAM_CODEC, RoadLoadedChunksRequestPacket::handle);
        registrar.playToServer(RoadWorldSubmitPacket.TYPE,
                RoadWorldSubmitPacket.STREAM_CODEC, RoadWorldSubmitPacket::handle);
        registrar.playToServer(RoadTeleportPacket.TYPE,
                RoadTeleportPacket.STREAM_CODEC, RoadTeleportPacket::handle);
        registrar.playToClient(RoadLoadedChunksPacket.TYPE,
                RoadLoadedChunksPacket.STREAM_CODEC, RoadLoadedChunksPacket::handle);
    }
}
