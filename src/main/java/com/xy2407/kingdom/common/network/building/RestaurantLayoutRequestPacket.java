package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.RestaurantPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：让脚下这家餐厅重扫家具并落库，也就是玩家按下开业布置
 */
public record RestaurantLayoutRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RestaurantLayoutRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "restaurant_layout_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestaurantLayoutRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new RestaurantLayoutRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RestaurantLayoutRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RestaurantPanelService.reopen(player);
            }
        });
    }
}
