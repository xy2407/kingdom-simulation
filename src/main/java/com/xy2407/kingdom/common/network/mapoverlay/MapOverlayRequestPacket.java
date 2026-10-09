package com.xy2407.kingdom.common.network.mapoverlay;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求地图叠层数据，带上当前可视中心与半径，供服务端裁剪建筑列表。
 */
public record MapOverlayRequestPacket(int centerX, int centerZ, int radius) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapOverlayRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "map_overlay_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapOverlayRequestPacket> STREAM_CODEC =
            StreamCodec.of(MapOverlayRequestPacket::encode, MapOverlayRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MapOverlayRequestPacket packet) {
        buf.writeInt(packet.centerX);
        buf.writeInt(packet.centerZ);
        buf.writeVarInt(packet.radius);
    }

    private static MapOverlayRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int centerX = buf.readInt();
        int centerZ = buf.readInt();
        int radius = buf.readVarInt();
        return new MapOverlayRequestPacket(centerX, centerZ, radius);
    }

    public static void handle(MapOverlayRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MapOverlayHooks.requestHandler.accept(player, packet);
            }
        });
    }
}
