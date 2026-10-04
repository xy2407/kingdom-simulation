package com.xy2407.kingdom.common.network.map;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 一块地图数据的分片。
 *
 * <p>一张 257×257 的采样有 6.6 万个字节，一次发完会撞上包体积上限，
 * 所以按 {@link #CHUNK_BYTES} 切片，客户端按 {@code chunkIndex} 拼回去。</p>
 *
 * <p>{@code colors} 里每格是原版 {@code MapColor} 的打包 id，0 表示未探索。
 * 注意它是「无符号字节」，取用时务必 {@code & 0xFF}。</p>
 *
 * @param originX    这块图左上角的世界 X
 * @param originZ    这块图左上角的世界 Z
 * @param size       边长
 * @param chunkIndex 分片序号，从 0 起
 * @param chunkTotal 分片总数
 * @param colors     该分片的像素数据
 */
public record WorldMapDataPacket(int originX, int originZ, int size,
                                 int chunkIndex, int chunkTotal, byte[] colors) implements CustomPacketPayload {

    /** 每片的字节数。8K 足够小，不会撞上限，包数也不至于太多。 */
    public static final int CHUNK_BYTES = 8192;

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
        byte[] colors = packet.colors();
        buf.writeVarInt(colors == null ? 0 : colors.length);
        if (colors != null) {
            buf.writeBytes(colors);
        }
    }

    private static WorldMapDataPacket decode(RegistryFriendlyByteBuf buf) {
        int originX = buf.readVarInt();
        int originZ = buf.readVarInt();
        int size = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int chunkTotal = buf.readVarInt();
        int length = buf.readVarInt();
        byte[] colors = new byte[Math.max(0, Math.min(CHUNK_BYTES * 2, length))];
        if (colors.length > 0) {
            buf.readBytes(colors);
        }
        return new WorldMapDataPacket(originX, originZ, size, chunkIndex, chunkTotal, colors);
    }

    public static void handle(WorldMapDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> WorldMapHooks.dataSink.accept(packet));
    }
}
