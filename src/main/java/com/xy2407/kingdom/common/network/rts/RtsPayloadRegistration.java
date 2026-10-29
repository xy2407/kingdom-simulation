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
        registrar.playToServer(RtsTeamCreatePacket.TYPE,
                RtsTeamCreatePacket.STREAM_CODEC, RtsTeamCreatePacket::handle);
        registrar.playToServer(RtsTeamEditPacket.TYPE,
                RtsTeamEditPacket.STREAM_CODEC, RtsTeamEditPacket::handle);
        registrar.playToServer(RtsTeamEditStatePacket.TYPE,
                RtsTeamEditStatePacket.STREAM_CODEC, RtsTeamEditStatePacket::handle);
        registrar.playToServer(RtsTeamOpPacket.TYPE,
                RtsTeamOpPacket.STREAM_CODEC, RtsTeamOpPacket::handle);
        registrar.playToServer(RtsTeamDeletePacket.TYPE,
                RtsTeamDeletePacket.STREAM_CODEC, RtsTeamDeletePacket::handle);
        registrar.playToServer(RtsTeamJoinPacket.TYPE,
                RtsTeamJoinPacket.STREAM_CODEC, RtsTeamJoinPacket::handle);
        registrar.playToServer(RtsTeamLeavePacket.TYPE,
                RtsTeamLeavePacket.STREAM_CODEC, RtsTeamLeavePacket::handle);
        registrar.playToServer(RtsTeamFocusPacket.TYPE,
                RtsTeamFocusPacket.STREAM_CODEC, RtsTeamFocusPacket::handle);
        registrar.playToServer(RtsTeamMovePacket.TYPE,
                RtsTeamMovePacket.STREAM_CODEC, RtsTeamMovePacket::handle);
        registrar.playToClient(RtsTeamMemberPosPacket.TYPE,
                RtsTeamMemberPosPacket.STREAM_CODEC, RtsTeamMemberPosPacket::handle);
        registrar.playToClient(RtsTeamOpResultPacket.TYPE,
                RtsTeamOpResultPacket.STREAM_CODEC, RtsTeamOpResultPacket::handle);
        registrar.playToClient(RtsTeamRosterPacket.TYPE,
                RtsTeamRosterPacket.STREAM_CODEC, RtsTeamRosterPacket::handle);
        registrar.playToClient(RtsSelectionCorrectionPacket.TYPE,
                RtsSelectionCorrectionPacket.STREAM_CODEC, RtsSelectionCorrectionPacket::handle);
        registrar.playToClient(RtsTeamSyncPacket.TYPE,
                RtsTeamSyncPacket.STREAM_CODEC, RtsTeamSyncPacket::handle);
    }
}
