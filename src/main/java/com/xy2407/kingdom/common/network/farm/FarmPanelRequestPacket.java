package com.xy2407.kingdom.common.network.farm;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.farm.FarmPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求农业面板状态，空载荷，位置由服务端判定
 */
public record FarmPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FarmPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "farm_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FarmPanelRequestPacket> STREAM_CODEC =
            StreamCodec.of(FarmPanelRequestPacket::encode, FarmPanelRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, FarmPanelRequestPacket packet) {
    }

    private static FarmPanelRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new FarmPanelRequestPacket();
    }

    public static void handle(FarmPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FarmPanelService.respond(player);
            }
        });
    }
}
