package com.xy2407.kingdom.common.network.map;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.map.WorldMapServer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求一块地图数据。
 *
 * <p>采世界表面必须在服务端做——{@code Heightmap} 只有服务端有，
 * 客户端要自己从世界顶部往下扫，一列上百次 {@code getBlockState}，视野一大就卡死。
 * 所以客户端只说"我要哪一块"，采样与分包都由 {@link WorldMapServer} 负责。</p>
 *
 * @param centerX 视图中心的世界 X
 * @param centerZ 视图中心的世界 Z
 * @param radius  采样半径（方块），结果边长 = radius × 2 + 1
 */
public record WorldMapRequestPacket(int centerX, int centerZ, int radius) implements CustomPacketPayload {

    /** 单次请求的半径上限，防止一次拉一张超大图把服务端堵住。 */
    public static final int MAX_RADIUS = 512;

    public static final CustomPacketPayload.Type<WorldMapRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "world_map_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldMapRequestPacket> STREAM_CODEC =
            StreamCodec.of(WorldMapRequestPacket::encode, WorldMapRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WorldMapRequestPacket packet) {
        buf.writeVarInt(packet.centerX());
        buf.writeVarInt(packet.centerZ());
        buf.writeVarInt(Math.max(1, Math.min(MAX_RADIUS, packet.radius())));
    }

    private static WorldMapRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int centerX = buf.readVarInt();
        int centerZ = buf.readVarInt();
        int radius = Math.max(1, Math.min(MAX_RADIUS, buf.readVarInt()));
        return new WorldMapRequestPacket(centerX, centerZ, radius);
    }

    public static void handle(WorldMapRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                WorldMapServer.fulfill(player, packet.centerX(), packet.centerZ(), packet.radius());
            }
        });
    }
}
