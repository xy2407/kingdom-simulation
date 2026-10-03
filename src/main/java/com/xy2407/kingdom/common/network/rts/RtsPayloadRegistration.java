package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * RTS 网络包注册点。写法沿用工程里已有的 {@code DatePayloadRegistration}。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RtsPayloadRegistration {

    private RtsPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // 客户端 → 服务端
        registrar.playToServer(RtsSelectionSyncPacket.TYPE,
                RtsSelectionSyncPacket.STREAM_CODEC, RtsSelectionSyncPacket::handle);
        registrar.playToServer(RtsMoveCommandPacket.TYPE,
                RtsMoveCommandPacket.STREAM_CODEC, RtsMoveCommandPacket::handle);
        registrar.playToServer(RtsWaypointMovePacket.TYPE,
                RtsWaypointMovePacket.STREAM_CODEC, RtsWaypointMovePacket::handle);
        registrar.playToServer(RtsFakePlayerSpawnPacket.TYPE,
                RtsFakePlayerSpawnPacket.STREAM_CODEC, RtsFakePlayerSpawnPacket::handle);
        registrar.playToServer(RtsFakePlayerClearPacket.TYPE,
                RtsFakePlayerClearPacket.STREAM_CODEC, RtsFakePlayerClearPacket::handle);
        // 服务端 → 客户端
        registrar.playToClient(RtsSelectionCorrectionPacket.TYPE,
                RtsSelectionCorrectionPacket.STREAM_CODEC, RtsSelectionCorrectionPacket::handle);
    }
}
