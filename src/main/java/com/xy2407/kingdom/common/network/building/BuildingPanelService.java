package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.builder.BuilderOpenPacket;
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
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(hit);
        if (meta != null && meta.isWork(BuildingWork.BUILDER_HUT)) {
            PacketDistributor.sendToPlayer(player, new BuilderOpenPacket());
            return;
        }
        PacketDistributor.sendToPlayer(player, new BuildingPanelStatePacket(
                hit.name(), hit.level(), hit.farmSlots(), pageOf(meta),
                hit.min().getX() + ", " + hit.min().getY() + ", " + hit.min().getZ()));
    }

    private static String pageOf(BuildingMeta meta) {
        if (meta == null || meta.type() == null) {
            return "";
        }
        if (meta.isType(BuildingType.AGRICULTURE)) {
            return "buildings/farm.html";
        }
        if (meta.isType(BuildingType.LIVESTOCK)) {
            return "buildings/ranch.html";
        }
        if (meta.isType(BuildingType.RESIDENTIAL)) {
            return "buildings/residential.html";
        }
        if (meta.isType(BuildingType.MILITARY)) {
            return "buildings/military.html";
        }
        if (meta.isType(BuildingType.INDUSTRY)) {
            return "buildings/industry.html";
        }
        if (meta.isType(BuildingType.COMMERCIAL)) {
            return meta.isWork(BuildingWork.SHOP)
                    ? "buildings/shop.html" : "buildings/restaurant.html";
        }
        if (meta.isWork(BuildingWork.WAREHOUSE)) {
            return "buildings/warehouse.html";
        }
        return "";
    }
}
