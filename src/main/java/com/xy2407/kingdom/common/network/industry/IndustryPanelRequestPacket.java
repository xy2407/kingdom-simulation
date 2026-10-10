package com.xy2407.kingdom.common.network.industry;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.industry.IndustryPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求工业面板状态，空载荷，建筑由服务端按玩家位置判定
 */
public record IndustryPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IndustryPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "industry_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IndustryPanelRequestPacket> STREAM_CODEC =
            StreamCodec.of(IndustryPanelRequestPacket::encode, IndustryPanelRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, IndustryPanelRequestPacket packet) {
    }

    private static IndustryPanelRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new IndustryPanelRequestPacket();
    }

    public static void handle(IndustryPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                IndustryPanelService.respond(player);
            }
        });
    }
}
