package com.xy2407.kingdom.common.network.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前维度里所有已完工建筑的清单（只含画框与点选所需的最小信息）。
 * 地图叠层那份是按领地过滤且只有 X/Z，这里是全量三维包围盒，两者用途不同。
 */
public record BuildingSyncPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(UUID id, String name, BlockPos min, BlockPos max, int rotation) {
    }

    private static final int MAX_ENTRIES = 1024;

    public static final CustomPacketPayload.Type<BuildingSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingSyncPacket> STREAM_CODEC =
            StreamCodec.of(BuildingSyncPacket::encode, BuildingSyncPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingSyncPacket packet) {
        buf.writeInt(packet.entries().size());
        for (Entry entry : packet.entries()) {
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.name() == null ? "" : entry.name(), 128);
            buf.writeBlockPos(entry.min());
            buf.writeBlockPos(entry.max());
            buf.writeInt(entry.rotation());
        }
    }

    private static BuildingSyncPacket decode(RegistryFriendlyByteBuf buf) {
        List<Entry> entries = new ArrayList<>();
        int count = Math.min(Math.max(buf.readInt(), 0), MAX_ENTRIES);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readUUID(), buf.readUtf(128), buf.readBlockPos(), buf.readBlockPos(),
                    buf.readInt()));
        }
        return new BuildingSyncPacket(entries);
    }

    public static void handle(BuildingSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingMoveHooks.syncSink.accept(packet));
    }
}
