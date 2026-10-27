package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsTeamService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RTS 建队（客户端→服务端）：用当前框选成员组队，首位为队长。 */
public record RtsTeamCreatePacket(List<UUID> members, int shape) implements CustomPacketPayload {

    public static final Type<RtsTeamCreatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamCreatePacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamCreatePacket::encode, RtsTeamCreatePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamCreatePacket packet) {
        buf.writeInt(packet.members().size());
        for (UUID member : packet.members()) {
            buf.writeUUID(member);
        }
        buf.writeInt(packet.shape());
    }

    public static RtsTeamCreatePacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readInt(), 0), 256);
        List<UUID> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(buf.readUUID());
        }
        return new RtsTeamCreatePacket(members, buf.readInt());
    }

    public static void handle(RtsTeamCreatePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsTeamService.createFromSelection(player, packet.members(), packet.shape()));
    }
}
