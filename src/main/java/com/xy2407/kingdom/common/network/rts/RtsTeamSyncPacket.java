package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.clientbound.RtsTeamClientBridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RTS 队伍同步（服务端→客户端）：下发归属玩家的全部队伍、成员与阵型状态。 */
public record RtsTeamSyncPacket(List<Entry> teams) implements CustomPacketPayload {

    public record Entry(UUID teamId, UUID leader, List<UUID> members, int shape, boolean saved) {

        public Entry {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public static final Type<RtsTeamSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamSyncPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamSyncPacket::encode, RtsTeamSyncPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RtsTeamSyncPacket packet) {
        buf.writeInt(packet.teams().size());
        for (Entry entry : packet.teams()) {
            buf.writeUUID(entry.teamId());
            buf.writeUUID(entry.leader());
            buf.writeInt(entry.members().size());
            for (UUID member : entry.members()) {
                buf.writeUUID(member);
            }
            buf.writeInt(entry.shape());
            buf.writeBoolean(entry.saved());
        }
    }

    public static RtsTeamSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readInt(), 0), 64);
        List<Entry> teams = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID teamId = buf.readUUID();
            UUID leader = buf.readUUID();
            int memberCount = Math.min(Math.max(buf.readInt(), 0), 256);
            List<UUID> members = new ArrayList<>(memberCount);
            for (int k = 0; k < memberCount; k++) {
                members.add(buf.readUUID());
            }
            teams.add(new Entry(teamId, leader, members, buf.readInt(), buf.readBoolean()));
        }
        return new RtsTeamSyncPacket(teams);
    }

    public static void handle(RtsTeamSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RtsTeamClientBridge.accept(packet.teams()));
    }
}
