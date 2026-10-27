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

/** RTS 阵型单成员偏移（客户端→服务端）：写入偏移并让该成员立刻走到新站位。 */
public record RtsTeamEditPacket(UUID teamId, UUID member, float offX, float offZ)
        implements CustomPacketPayload {

    public static final Type<RtsTeamEditPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamEditPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamEditPacket::encode, RtsTeamEditPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamEditPacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeUUID(packet.member());
        buf.writeFloat(packet.offX());
        buf.writeFloat(packet.offZ());
    }

    public static RtsTeamEditPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamEditPacket(buf.readUUID(), buf.readUUID(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(RtsTeamEditPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.editMember(player, packet.teamId(), packet.member(),
                packet.offX(), packet.offZ()));
    }
}
