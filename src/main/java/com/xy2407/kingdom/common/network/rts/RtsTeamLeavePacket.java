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
 * 客户端 → 服务端：把一名市民移出队伍，移除队长等同于解散整队
 */
public record RtsTeamLeavePacket(UUID teamId, UUID memberUuid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamLeavePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_leave"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamLeavePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamLeavePacket::encode, RtsTeamLeavePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamLeavePacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeUUID(packet.memberUuid());
    }

    private static RtsTeamLeavePacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamLeavePacket(buf.readUUID(), buf.readUUID());
    }

    public static void handle(RtsTeamLeavePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            String error = RtsTeamService.leave(player, packet.teamId(), packet.memberUuid());
            if (error != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(error), true);
            }
        });
    }
}
