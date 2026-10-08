package com.xy2407.kingdom.common.network.map;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 一块地图数据的分片：调色板打包 id 按 VarInt 切片下发，客户端按序号拼回。
 */
public record WorldMapDataPacket(int originX, int originZ, int size,
                                 int chunkIndex, int chunkTotal, int revision,
                                 int[] colors) implements CustomPacketPayload {

    public static final int CHUNK_INTS = 4096;

    public static final int NO_CHANGE = 0;

    public static final CustomPacketPayload.Type<WorldMapDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "world_map_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldMapDataPacket> STREAM_CODEC =
            StreamCodec.of(WorldMapDataPacket::encode, WorldMapDataPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WorldMapDataPacket packet) {
        buf.writeVarInt(packet.originX());
        buf.writeVarInt(packet.originZ());
        buf.writeVarInt(packet.size());
        buf.writeVarInt(packet.chunkIndex());
        buf.writeVarInt(Math.max(1, packet.chunkTotal()));
        buf.writeVarInt(packet.revision());
        int[] colors = packet.colors();
        buf.writeVarInt(colors == null ? 0 : colors.length);
        if (colors != null) {
            for (int color : colors) {
                buf.writeVarInt(color);
            }
        }
    }

    private static WorldMapDataPacket decode(RegistryFriendlyByteBuf buf) {
        int originX = buf.readVarInt();
        int originZ = buf.readVarInt();
        int size = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int chunkTotal = buf.readVarInt();
        int revision = buf.readVarInt();
        int length = buf.readVarInt();
        int[] colors = new int[Math.max(0, Math.min(CHUNK_INTS * 2, length))];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = buf.readVarInt();
        }
        return new WorldMapDataPacket(originX, originZ, size, chunkIndex, chunkTotal, revision, colors);
    }

    public static void handle(WorldMapDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> WorldMapHooks.dataSink.accept(packet));
    }
}
