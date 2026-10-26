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
 * 客户端 → 服务端：把脚下商铺切到指定营业状态，开业时顺带重扫货架
 */
public record ShopPanelActionPacket(boolean open) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "shop_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(ShopPanelActionPacket::encode, ShopPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ShopPanelActionPacket packet) {
        buf.writeBoolean(packet.open());
    }

    private static ShopPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ShopPanelActionPacket(buf.readBoolean());
    }

    public static void handle(ShopPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopPanelService.apply(player, packet.open());
            }
        });
    }
}
