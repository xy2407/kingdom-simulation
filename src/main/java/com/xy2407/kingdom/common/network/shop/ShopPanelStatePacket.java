package com.xy2407.kingdom.common.network.shop;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：商铺面板整份状态，营业开关、营业员名与货架数量
 */
public record ShopPanelStatePacket(String name, int level, boolean open, String worker, int shelves)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "shop_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(ShopPanelStatePacket::encode, ShopPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ShopPanelStatePacket packet) {
        buf.writeUtf(packet.name() == null ? "" : packet.name(), 128);
        buf.writeVarInt(packet.level());
        buf.writeBoolean(packet.open());
        buf.writeUtf(packet.worker() == null ? "" : packet.worker(), 64);
        buf.writeVarInt(packet.shelves());
    }

    private static ShopPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        return new ShopPanelStatePacket(buf.readUtf(128), buf.readVarInt(), buf.readBoolean(),
                buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(ShopPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ShopPanelHooks.stateSink.accept(packet));
    }
}
