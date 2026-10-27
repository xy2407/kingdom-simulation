package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsTeamService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** RTS 队伍操作（客户端→服务端）：保存阵型 / 解散 / 按形状重排。 */
public record RtsTeamOpPacket(UUID teamId, int action, int shape) implements CustomPacketPayload {

    public static final int ACTION_SAVE = 0;
    public static final int ACTION_DISSOLVE = 1;
    public static final int ACTION_SHAPE = 2;

    public static final Type<RtsTeamOpPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_op"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamOpPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamOpPacket::encode, RtsTeamOpPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamOpPacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeInt(packet.action());
        buf.writeInt(packet.shape());
    }

    public static RtsTeamOpPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamOpPacket(buf.readUUID(), buf.readInt(), buf.readInt());
    }

    public static void handle(RtsTeamOpPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.apply(player, packet.teamId(), packet.action(), packet.shape()));
    }
}
