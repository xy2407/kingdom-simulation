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

/** RTS 阵型编辑器开关（客户端→服务端）：开编辑时冻结跟随，关编辑时恢复。 */
public record RtsTeamEditStatePacket(UUID teamId, boolean editing) implements CustomPacketPayload {

    public static final Type<RtsTeamEditStatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_edit_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamEditStatePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamEditStatePacket::encode, RtsTeamEditStatePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamEditStatePacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeBoolean(packet.editing());
    }

    public static RtsTeamEditStatePacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamEditStatePacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(RtsTeamEditStatePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.setEditing(player, packet.teamId(), packet.editing()));
    }
}
