package com.xy2407.kingdom.common.network.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.clientbound.RtsTeamPosBridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：队伍成员的世界坐标快照，供阵型编辑器渲染与网格对齐
 */
public record RtsTeamMemberPosPacket(UUID teamId, List<MemberPos> members)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamMemberPosPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_member_pos"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamMemberPosPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamMemberPosPacket::encode, RtsTeamMemberPosPacket::decode);

    public RtsTeamMemberPosPacket {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public record MemberPos(UUID uuid, double x, double y, double z) {
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamMemberPosPacket packet) {
        buf.writeUUID(packet.teamId());
        List<MemberPos> members = packet.members() == null ? List.of() : packet.members();
        int count = Math.min(members.size(), 512);
        buf.writeInt(count);
        for (int index = 0; index < count; index++) {
            MemberPos member = members.get(index);
            buf.writeUUID(member.uuid());
            buf.writeDouble(member.x());
            buf.writeDouble(member.y());
            buf.writeDouble(member.z());
        }
    }

    private static RtsTeamMemberPosPacket decode(RegistryFriendlyByteBuf buf) {
        UUID teamId = buf.readUUID();
        int size = Math.min(Math.max(buf.readInt(), 0), 512);
        List<MemberPos> list = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            list.add(new MemberPos(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble()));
        }
        return new RtsTeamMemberPosPacket(teamId, list);
    }

    public static void handle(RtsTeamMemberPosPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RtsTeamPosBridge.accept(packet.teamId(), packet.members()));
    }
}
