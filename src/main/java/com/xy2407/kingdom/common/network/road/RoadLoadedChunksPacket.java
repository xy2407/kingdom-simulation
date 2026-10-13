package com.xy2407.kingdom.common.network.road;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：以请求玩家为中心、服务端已加载的区块 key 集合（key = (chunkX<<32)|(chunkZ&0xFFFFFFFF)）。
 */
public record RoadLoadedChunksPacket(long[] keys) implements CustomPacketPayload {

    private static final int MAX_KEYS = 4096;

    public static final CustomPacketPayload.Type<RoadLoadedChunksPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "road_loaded_chunks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadLoadedChunksPacket> STREAM_CODEC =
            StreamCodec.of(RoadLoadedChunksPacket::encode, RoadLoadedChunksPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RoadLoadedChunksPacket packet) {
        int count = Math.min(packet.keys().length, MAX_KEYS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeLong(packet.keys()[index]);
        }
    }

    private static RoadLoadedChunksPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_KEYS);
        long[] keys = new long[count];
        for (int index = 0; index < count; index++) {
            keys[index] = buf.readLong();
        }
        return new RoadLoadedChunksPacket(keys);
    }

    public static void handle(RoadLoadedChunksPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RoadHooks.loadedSink.accept(packet.keys()));
    }
}
