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

/**
 * RTS 阵型编辑器状态（客户端→服务端）：editing 开关编辑态，worldOps 表示世界空间操作（解冻但仍不跟随）
 */
public record RtsTeamEditStatePacket(UUID teamId, boolean editing, boolean worldOps)
        implements CustomPacketPayload {

    public static final Type<RtsTeamEditStatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_edit_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamEditStatePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamEditStatePacket::encode, RtsTeamEditStatePacket::decode);

    public RtsTeamEditStatePacket(UUID teamId, boolean editing) {
        this(teamId, editing, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamEditStatePacket packet) {
        buf.writeUUID(packet.teamId());
        buf.writeBoolean(packet.editing());
        buf.writeBoolean(packet.worldOps());
    }

    public static RtsTeamEditStatePacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsTeamEditStatePacket(buf.readUUID(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(RtsTeamEditStatePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.setEditing(player, packet.teamId(),
                packet.editing(), packet.worldOps()));
    }
}
