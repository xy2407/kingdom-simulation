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
 * <p>一张 385×385 的采样有 14.8 万格，一次发完会撞上包体积上限，
 * 所以按 {@link #CHUNK_INTS} 切片，客户端按 {@code chunkIndex} 拼回去。</p>
 *
 * <p>{@code colors} 里每格是原版 {@code MapColor} 的<b>打包 id</b>（含明暗档），
 * {@code -1} 表示未探索。与 {@code WorldMapDataPacket} 发送方的
 * {@code WorldSurfaceSampler.UNEXPLORED} 对应。</p>
 *
 * <p><b>传输用 VarInt 逐个编码。</b>打包 id 都在 0~255，VarInt 里绝大多数只占 2 字节
 * （第七位标志），比定长 int 省不少。</p>
 *
 * @param originX    这块图左上角的世界 X
 * @param originZ    这块图左上角的世界 Z
 * @param size       边长
 * @param chunkIndex 分片序号，从 0 起
 * @param chunkTotal 分片总数
 * @param colors     该分片的调色板打包 id 数据
 */
public record WorldMapDataPacket(int originX, int originZ, int size,
                                 int chunkIndex, int chunkTotal, int revision,
                                 int[] colors) implements CustomPacketPayload {

    /** 每片的格数。4096 格最多约 20KB，包体不至于过大，片数也不至于太多。 */
    public static final int CHUNK_INTS = 4096;

    /**
     * {@code size} 取这个值表示"没有变化"，不是一个真块图。
     *
     * <p>服务端比对内容版本后，若客户端手上那份已经是最新的，就只回这么一片，
     * 客户端收到后原样保留手上的数据。整个来回几十字节。</p>
     */
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
