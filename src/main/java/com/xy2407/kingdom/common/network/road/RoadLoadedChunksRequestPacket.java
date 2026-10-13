package com.xy2407.kingdom.common.network.road;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.road.RoadService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求下发"当前维度、以玩家为中心的一片范围内服务端已加载的区块"，供地图画线的 D1 把关。
 */
public record RoadLoadedChunksRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RoadLoadedChunksRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "road_loaded_chunks_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadLoadedChunksRequestPacket> STREAM_CODEC =
            StreamCodec.of(RoadLoadedChunksRequestPacket::encode, RoadLoadedChunksRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RoadLoadedChunksRequestPacket packet) {
    }

    private static RoadLoadedChunksRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new RoadLoadedChunksRequestPacket();
    }

    public static void handle(RoadLoadedChunksRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RoadService.sendLoadedChunks(player);
            }
        });
    }
}
