package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 建筑网络包注册，绑定投影下发与放置确认两个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuildingPayloadRegistration {

    private BuildingPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(BuildingPlaceConfirmPacket.TYPE,
                BuildingPlaceConfirmPacket.STREAM_CODEC, BuildingPlaceConfirmPacket::handle);
        registrar.playToClient(BuildingSitePacket.TYPE,
                BuildingSitePacket.STREAM_CODEC, BuildingSitePacket::handle);
        registrar.playToServer(BuildingPanelRequestPacket.TYPE,
                BuildingPanelRequestPacket.STREAM_CODEC, BuildingPanelRequestPacket::handle);
        registrar.playToClient(BuildingPanelStatePacket.TYPE,
                BuildingPanelStatePacket.STREAM_CODEC, BuildingPanelStatePacket::handle);
        registrar.playToServer(ResidentListRequestPacket.TYPE,
                ResidentListRequestPacket.STREAM_CODEC, ResidentListRequestPacket::handle);
        registrar.playToServer(ResidentActionPacket.TYPE,
                ResidentActionPacket.STREAM_CODEC, ResidentActionPacket::handle);
        registrar.playToClient(ResidentListPacket.TYPE,
                ResidentListPacket.STREAM_CODEC, ResidentListPacket::handle);
        registrar.playToServer(RestaurantMenuRequestPacket.TYPE,
                RestaurantMenuRequestPacket.STREAM_CODEC, RestaurantMenuRequestPacket::handle);
        registrar.playToClient(RestaurantMenuPacket.TYPE,
                RestaurantMenuPacket.STREAM_CODEC, RestaurantMenuPacket::handle);
        registrar.playToServer(RestaurantLayoutRequestPacket.TYPE,
                RestaurantLayoutRequestPacket.STREAM_CODEC, RestaurantLayoutRequestPacket::handle);
        registrar.playToServer(BuildingSyncRequestPacket.TYPE,
                BuildingSyncRequestPacket.STREAM_CODEC, BuildingSyncRequestPacket::handle);
        registrar.playToClient(BuildingSyncPacket.TYPE,
                BuildingSyncPacket.STREAM_CODEC, BuildingSyncPacket::handle);
        registrar.playToServer(BuildingMovePacket.TYPE,
                BuildingMovePacket.STREAM_CODEC, BuildingMovePacket::handle);
        registrar.playToClient(MapStandDataPacket.TYPE,
                MapStandDataPacket.STREAM_CODEC, MapStandDataPacket::handle);
        registrar.playToServer(MapStandTogglePacket.TYPE,
                MapStandTogglePacket.STREAM_CODEC, MapStandTogglePacket::handle);
    }
}
