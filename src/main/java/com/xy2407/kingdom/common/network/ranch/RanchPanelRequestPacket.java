package com.xy2407.kingdom.common.network.ranch;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.ranch.RanchPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求养殖面板状态，空载荷，建筑由服务端按玩家位置判定
 */
public record RanchPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RanchPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "ranch_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RanchPanelRequestPacket> STREAM_CODEC =
            StreamCodec.of(RanchPanelRequestPacket::encode, RanchPanelRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RanchPanelRequestPacket packet) {
    }

    private static RanchPanelRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new RanchPanelRequestPacket();
    }

    public static void handle(RanchPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RanchPanelService.respond(player);
            }
        });
    }
}
