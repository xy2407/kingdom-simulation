package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 建筑面板的服务端侧：按玩家实际位置判定所处建筑，并决定该开哪个操作面板。
 */
public final class BuildingPanelService {

    private BuildingPanelService() {
    }

    static void respond(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BuildingRecord hit = BuildingRegistry.INSTANCE.locate(player);
        if (hit == null) {
            PacketDistributor.sendToPlayer(player, BuildingPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, new BuildingPanelStatePacket(
                hit.name(), hit.level(), hit.farmSlots(), pageOf(BuildingRegistry.INSTANCE.metaOf(hit)),
                hit.min().getX() + ", " + hit.min().getY() + ", " + hit.min().getZ()));
    }

    private static String pageOf(BuildingMeta meta) {
        if (meta == null || meta.type() == null) {
            return "";
        }
        return switch (meta.type()) {
            case AGRICULTURE -> "buildings/farm.html";
            case INDUSTRY -> "buildings/industry.html";
            case LIVESTOCK -> "buildings/ranch.html";
            case COMMERCIAL -> meta.isShop()
                    ? "buildings/shop.html"
                    : meta.isRestaurant() ? "buildings/restaurant.html" : "";
            case MILITARY -> "buildings/military.html";
            case RESIDENTIAL -> "buildings/residential.html";
            case STORAGE -> "buildings/warehouse.html";
            default -> "";
        };
    }
}
