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
 * 客户端请求一块地图数据：采样与分包都在服务端做，版本号用于避免重复下发。
 */
public record WorldMapRequestPacket(int centerX, int centerZ, int radius, int knownRevision)
        implements CustomPacketPayload {

    public static final int NO_REVISION = -1;

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
        buf.writeVarInt(packet.knownRevision());
    }

    private static WorldMapRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int centerX = buf.readVarInt();
        int centerZ = buf.readVarInt();
        int radius = Math.max(1, Math.min(MAX_RADIUS, buf.readVarInt()));
        int knownRevision = buf.readVarInt();
        return new WorldMapRequestPacket(centerX, centerZ, radius, knownRevision);
    }

    public static void handle(WorldMapRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                WorldMapServer.fulfill(player, packet.centerX(), packet.centerZ(),
                        packet.radius(), packet.knownRevision());
            }
        });
    }
}
