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
 * 客户端 → 服务端：请求传送到一个有道路的区块（D3：服务端校验自家领地内或自己提交过）。
 */
public record RoadTeleportPacket(int chunkX, int chunkZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RoadTeleportPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "road_teleport"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadTeleportPacket> STREAM_CODEC =
            StreamCodec.of(RoadTeleportPacket::encode, RoadTeleportPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RoadTeleportPacket packet) {
        buf.writeInt(packet.chunkX());
        buf.writeInt(packet.chunkZ());
    }

    private static RoadTeleportPacket decode(RegistryFriendlyByteBuf buf) {
        return new RoadTeleportPacket(buf.readInt(), buf.readInt());
    }

    public static void handle(RoadTeleportPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RoadService.teleportToRoad(player, packet.chunkX(), packet.chunkZ());
            }
        });
    }
}
