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
 * 客户端 → 服务端：要一份当前所处餐厅的菜单（建筑按玩家位置判定）。
 */
public record RestaurantMenuRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RestaurantMenuRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "restaurant_menu_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestaurantMenuRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new RestaurantMenuRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RestaurantMenuRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RestaurantPanelService.sendMenu(player);
            }
        });
    }
}
