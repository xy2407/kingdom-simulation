package com.xy2407.kingdom.common.network.employment;

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
 * 服务端 → 客户端：雇佣界面的整份状态，候选市民、上限与已选名单一起下发
 */
public record EmploymentPanelStatePacket(String buildingId, String targetId, int kind,
                                         String kindLabel, int limit, List<Entry> entries,
                                         List<UUID> selected) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EmploymentPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "employment_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmploymentPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(EmploymentPanelStatePacket::encode, EmploymentPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(UUID id, String name, String skin, String job, String note, boolean hired) {
        public Entry {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            job = job == null ? "" : job;
            note = note == null ? "" : note;
        }
    }

    public static EmploymentPanelStatePacket empty() {
        return new EmploymentPanelStatePacket("", "", 0, "", 0, List.of(), List.of());
    }

    /** 名单上限，两端必须一致：encode 写多少、decode 就必须读多少 */
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_SELECTED = 512;

    public static void encode(RegistryFriendlyByteBuf buffer, EmploymentPanelStatePacket packet) {
        buffer.writeUtf(packet.buildingId, 64);
        buffer.writeUtf(packet.targetId, 64);
        buffer.writeVarInt(packet.kind);
        buffer.writeUtf(packet.kindLabel, 32);
        buffer.writeVarInt(packet.limit);
        int entryCount = Math.min(packet.entries.size(), MAX_ENTRIES);
        buffer.writeVarInt(entryCount);
        for (int index = 0; index < entryCount; index++) {
            Entry entry = packet.entries.get(index);
            buffer.writeUUID(entry.id);
            buffer.writeUtf(entry.name, 64);
            buffer.writeUtf(entry.skin, 256);
            buffer.writeUtf(entry.job, 64);
            buffer.writeUtf(entry.note, 64);
            buffer.writeBoolean(entry.hired);
        }
        int selectedCount = Math.min(packet.selected.size(), MAX_SELECTED);
        buffer.writeVarInt(selectedCount);
        for (int index = 0; index < selectedCount; index++) {
            buffer.writeUUID(packet.selected.get(index));
        }
    }

    public static EmploymentPanelStatePacket decode(RegistryFriendlyByteBuf buffer) {
        String buildingId = buffer.readUtf(64);
        String targetId = buffer.readUtf(64);
        int kind = buffer.readVarInt();
        String kindLabel = buffer.readUtf(32);
        int limit = buffer.readVarInt();
        int count = Math.min(Math.max(buffer.readVarInt(), 0), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buffer.readUUID(), buffer.readUtf(64), buffer.readUtf(256),
                    buffer.readUtf(64), buffer.readUtf(64), buffer.readBoolean()));
        }
        int chosen = Math.min(Math.max(buffer.readVarInt(), 0), MAX_SELECTED);
        List<UUID> selected = new ArrayList<>(chosen);
        for (int index = 0; index < chosen; index++) {
            selected.add(buffer.readUUID());
        }
        return new EmploymentPanelStatePacket(buildingId, targetId, kind, kindLabel, limit, entries,
                selected);
    }

    public static void handle(EmploymentPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> EmploymentHooks.openSink.accept(packet));
    }
}
