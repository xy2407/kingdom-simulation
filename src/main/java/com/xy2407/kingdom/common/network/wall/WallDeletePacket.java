package com.xy2407.kingdom.common.network.wall;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.wall.WallService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：删除选中的城墙节点或墙段，数据库与实际方块一并清除
 */
public record WallDeletePacket(String key, boolean node) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WallDeletePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallDeletePacket> STREAM_CODEC =
            StreamCodec.of(WallDeletePacket::encode, WallDeletePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WallDeletePacket packet) {
        buf.writeUtf(packet.key() == null ? "" : packet.key(), 256);
        buf.writeBoolean(packet.node());
    }

    private static WallDeletePacket decode(RegistryFriendlyByteBuf buf) {
        return new WallDeletePacket(buf.readUtf(256), buf.readBoolean());
    }

    public static void handle(WallDeletePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                WallService.delete(player, packet.key(), packet.node());
            }
        });
    }
}
