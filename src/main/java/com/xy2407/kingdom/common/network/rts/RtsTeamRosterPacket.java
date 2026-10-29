package com.xy2407.kingdom.common.network.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.clientbound.RtsTeamRosterBridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：队伍成员名单（名字 + 皮肤），供 AUI 面板渲染头像与姓名
 */
public record RtsTeamRosterPacket(List<Member> members) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsTeamRosterPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_team_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsTeamRosterPacket> STREAM_CODEC =
            StreamCodec.of(RtsTeamRosterPacket::encode, RtsTeamRosterPacket::decode);

    public RtsTeamRosterPacket {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public record Member(UUID uuid, String name, String skin, String job) {
        public Member {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            job = job == null ? "" : job;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsTeamRosterPacket packet) {
        buf.writeInt(packet.members().size());
        for (Member member : packet.members()) {
            buf.writeUUID(member.uuid());
            buf.writeUtf(member.name(), 64);
            buf.writeUtf(member.skin(), 200);
            buf.writeUtf(member.job(), 64);
        }
    }

    private static RtsTeamRosterPacket decode(RegistryFriendlyByteBuf buf) {
        int size = Math.min(Math.max(buf.readInt(), 0), 256);
        List<Member> list = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            list.add(new Member(buf.readUUID(), buf.readUtf(64), buf.readUtf(200),
                    buf.readUtf(64)));
        }
        return new RtsTeamRosterPacket(list);
    }

    public static void handle(RtsTeamRosterPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RtsTeamRosterBridge.accept(packet.members()));
    }
}
