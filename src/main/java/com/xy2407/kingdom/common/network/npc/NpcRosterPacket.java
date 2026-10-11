package com.xy2407.kingdom.common.network.npc;

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
 * 服务端 → 客户端：当前维度里的市民名册，供 RTS 左栏的人口页列表与相机跳转使用。
 */
public record NpcRosterPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(UUID id, String name, String skin, String note, int x, int y, int z) {
        public Entry {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    private static final int MAX_ENTRIES = 512;

    public static final CustomPacketPayload.Type<NpcRosterPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcRosterPacket> STREAM_CODEC =
            StreamCodec.of(NpcRosterPacket::encode, NpcRosterPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, NpcRosterPacket packet) {
        buf.writeVarInt(packet.entries().size());
        for (Entry entry : packet.entries()) {
            buf.writeUUID(entry.id() == null ? new UUID(0L, 0L) : entry.id());
            buf.writeUtf(entry.name(), 64);
            buf.writeUtf(entry.skin(), 128);
            buf.writeUtf(entry.note(), 16);
            buf.writeVarInt(entry.x());
            buf.writeVarInt(entry.y());
            buf.writeVarInt(entry.z());
        }
    }

    private static NpcRosterPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readUtf(128), buf.readUtf(16),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new NpcRosterPacket(entries);
    }

    public static void handle(NpcRosterPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcRosterHooks.rosterSink.accept(packet));
    }
}
