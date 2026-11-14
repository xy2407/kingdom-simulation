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
 * 客户端 → 服务端：进入城墙模式时请求一次本人领地的城墙状态
 */
public record WallStateRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WallStateRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_state_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallStateRequestPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> { }, buf -> new WallStateRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WallStateRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                WallService.sendState(player);
            }
        });
    }
}
