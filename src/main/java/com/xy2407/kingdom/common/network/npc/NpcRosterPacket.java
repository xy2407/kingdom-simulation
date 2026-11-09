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
 * 服务端 → 客户端：市民名册分块下发，携带总条数与偏移，客户端攒齐后再刷新列表。
 */
public record NpcRosterPacket(List<Entry> entries, int total, int offset)
        implements CustomPacketPayload {

    /**
     * @param id       市民 UUID（NpcData.getId），跨存档稳定，用于人口页标识
     * @param entityId 当前实体 UUID；只有实体已加载时才有值，RTS 选中/编组都用它。
     *                 注意两者不是同一个 UUID：市民 id 是数据层主键，实体 UUID 每次重新生成。
     */
    public record Entry(UUID id, UUID entityId, String name, String skin, String note,
                        int x, int y, int z) {
        public Entry {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    /** 单包上限，按每条约 46 字节算，256 条约 12KB，远离包长上限 */
    public static final int MAX_ENTRIES = 256;

    public static final CustomPacketPayload.Type<NpcRosterPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcRosterPacket> STREAM_CODEC =
            StreamCodec.of(NpcRosterPacket::encode, NpcRosterPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public boolean first() {
        return offset <= 0;
    }

    public boolean last() {
        return offset + entries.size() >= total;
    }

    private static void encode(RegistryFriendlyByteBuf buf, NpcRosterPacket packet) {
        List<Entry> list = packet.entries() == null ? List.of() : packet.entries();
        int count = Math.min(list.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            Entry entry = list.get(index);
            buf.writeUUID(entry.id() == null ? new UUID(0L, 0L) : entry.id());
            buf.writeBoolean(entry.entityId() != null);
            if (entry.entityId() != null) {
                buf.writeUUID(entry.entityId());
            }
            buf.writeUtf(entry.name(), 64);
            buf.writeUtf(entry.skin(), 128);
            buf.writeUtf(entry.note(), 16);
            buf.writeVarInt(entry.x());
            buf.writeVarInt(entry.y());
            buf.writeVarInt(entry.z());
        }
        buf.writeVarInt(Math.max(packet.total(), count));
        buf.writeVarInt(Math.max(packet.offset(), 0));
    }

    private static NpcRosterPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID id = buf.readUUID();
            UUID entityId = buf.readBoolean() ? buf.readUUID() : null;
            entries.add(new Entry(id, entityId, buf.readUtf(64), buf.readUtf(128), buf.readUtf(16),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        int total = Math.max(buf.readVarInt(), count);
        int offset = Math.max(buf.readVarInt(), 0);
        return new NpcRosterPacket(entries, total, offset);
    }

    public static void handle(NpcRosterPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcRosterHooks.rosterSink.accept(packet));
    }
}
