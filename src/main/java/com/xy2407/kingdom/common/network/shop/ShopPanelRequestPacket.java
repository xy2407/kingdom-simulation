package com.xy2407.kingdom.common.network.shop;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.shop.ShopPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：要一份当前所处商铺的面板状态，建筑按玩家位置判定
 */
public record ShopPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "shop_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPanelRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new ShopPanelRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopPanelService.respond(player);
            }
        });
    }
}
