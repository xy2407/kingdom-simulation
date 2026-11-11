package com.xy2407.kingdom.common.network.military;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 军事驻军面板快照包，下发军营等级、驻军名单、可征召市民与仓库物资数量
 */
public record MilitaryPanelStatePacket(boolean inside, UUID buildingId, String name, int level,
                                       int capacity, List<Member> soldiers,
                                       List<Member> candidates, int foodCount, int gearCount)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MilitaryPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "military_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MilitaryPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(MilitaryPanelStatePacket::encode, MilitaryPanelStatePacket::decode);

    public record Member(UUID id, String name, String skin, String note) {
        public Member {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    public MilitaryPanelStatePacket {
        name = name == null ? "" : name;
        soldiers = soldiers == null ? List.of() : List.copyOf(soldiers);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static MilitaryPanelStatePacket outside() {
        return new MilitaryPanelStatePacket(false, null, "", 1, 0, List.of(), List.of(), 0, 0);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MilitaryPanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUUID(packet.buildingId() == null ? new UUID(0L, 0L) : packet.buildingId());
        buf.writeUtf(packet.name(), 256);
        buf.writeVarInt(packet.level());
        buf.writeVarInt(packet.capacity());
        writeMembers(buf, packet.soldiers());
        writeMembers(buf, packet.candidates());
        buf.writeVarInt(packet.foodCount());
        buf.writeVarInt(packet.gearCount());
    }

    private static void writeMembers(RegistryFriendlyByteBuf buf, List<Member> members) {
        buf.writeVarInt(members.size());
        for (Member member : members) {
            buf.writeUUID(member.id());
            buf.writeUtf(member.name(), 96);
            buf.writeUtf(member.skin(), 64);
            buf.writeUtf(member.note(), 128);
        }
    }

    private static List<Member> readMembers(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Member> members = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(new Member(buf.readUUID(), buf.readUtf(96), buf.readUtf(64), buf.readUtf(128)));
        }
        return members;
    }

    private static MilitaryPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        UUID id = buf.readUUID();
        String name = buf.readUtf(256);
        int level = buf.readVarInt();
        int capacity = buf.readVarInt();
        List<Member> soldiers = readMembers(buf);
        List<Member> candidates = readMembers(buf);
        int food = buf.readVarInt();
        int gear = buf.readVarInt();
        return new MilitaryPanelStatePacket(inside, id, name, level, capacity, soldiers, candidates,
                food, gear);
    }

    public static void handle(MilitaryPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MilitaryPanelHooks.stateSink.accept(packet));
    }
}
