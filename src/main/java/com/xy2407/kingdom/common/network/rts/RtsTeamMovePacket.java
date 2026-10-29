package com.xy2407.kingdom.common.network.rts;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsTeamService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：整队按已保存阵型开往目标点，每名成员落在自己的偏移位
 */
public record RtsTeamMovePacket(UUID teamId, BlockPos target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamMovePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamMovePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamMovePacket::encode, RtsTeamMovePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamMovePacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeBlockPos(packet.target());
    }

    private static RtsTeamMovePacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamMovePacket(buf.readUUID(), buf.readBlockPos());
    }

    public static void handle(RtsTeamMovePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.moveTeam(player, packet.teamId(), packet.target()));
    }
}
