package com.xy2407.kingdom.common.network.rts;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsTeamService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：选中并前往该队伍的队长，用于面板上的定位按钮
 */
public record RtsTeamFocusPacket(UUID teamId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamFocusPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_focus"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamFocusPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamFocusPacket::encode, RtsTeamFocusPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamFocusPacket packet) {
        buf.writeUUID(packet.teamId());
    }

    private static RtsTeamFocusPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamFocusPacket(buf.readUUID());
    }

    public static void handle(RtsTeamFocusPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.focus(player, packet.teamId()));
    }
}
