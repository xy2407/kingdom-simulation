package com.xy2407.kingdom.common.network.building;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingWorkCatalog;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.network.builder.BuilderOpenPacket;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.industry.IndustryStaffService;
import com.xy2407.kingdom.server.military.MilitaryPanelService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 建筑面板服务端判定，按玩家位置定位建筑，未定型先选工种，定型后开对应功能页
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
        sendPanel(player, hit);
    }

    static void sendPanel(ServerPlayer player, BuildingRecord hit) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(hit);
        String display = meta != null && meta.name() != null && !meta.name().isBlank()
                ? meta.name() : hit.name();
        if (!hit.typed()) {
            List<BuildingWork> options = BuildingWorkCatalog.worksOf(hit.type());
            if (!options.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new BuildingWorkChoicePacket(
                        hit.id(), hit.type().label(), namesOf(options)));
                return;
            }
            PacketDistributor.sendToPlayer(player, new BuildingPanelStatePacket(
                    display, hit.level(), hit.farmSlots(), "",
                    hit.min().getX() + ", " + hit.min().getY() + ", " + hit.min().getZ()));
            return;
        }
        if (meta != null && meta.isWork(BuildingWork.BUILDER_HUT)) {
            PacketDistributor.sendToPlayer(player, new BuilderOpenPacket());
            return;
        }
        if (meta != null && meta.isWork(BuildingWork.BARRACKS)) {
            MilitaryPanelService.respond(player);
            return;
        }
        if (meta != null && meta.isWork(BuildingWork.FORGE)) {
            com.xy2407.kingdom.server.forge.ForgePanelService.respond(player);
            return;
        }
        if (meta != null && (meta.isWork(BuildingWork.MINE) || meta.isWork(BuildingWork.LUMBER))) {
            IndustryStaffService.open(player);
            return;
        }
        PacketDistributor.sendToPlayer(player, new BuildingPanelStatePacket(
                display, hit.level(), hit.farmSlots(), pageOf(meta),
                hit.min().getX() + ", " + hit.min().getY() + ", " + hit.min().getZ()));
    }

    private static List<String> namesOf(List<BuildingWork> works) {
        List<String> names = new ArrayList<>(works.size());
        for (BuildingWork work : works) {
            names.add(work.name());
        }
        return names;
    }

    static void applyWork(ServerPlayer player, BuildingWorkSelectPacket packet) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(packet.buildingId());
        if (building == null || !playerIn(player, building)) {
            return;
        }
        if (building.typed()) {
            player.displayClientMessage(Component.literal("这座建筑已经定过用途了"), true);
            return;
        }
        BuildingWork chosen = BuildingWork.parse(packet.work());
        if (!BuildingWorkCatalog.accepts(building.type(), chosen)) {
            player.displayClientMessage(Component.literal("这个用途不属于该建筑分类"), true);
            return;
        }
        int farmSlots = BuildingLevelRules.farmSlotsOf(chosen, building.level());
        BuildingRecord typed = new BuildingRecord(building.id(), building.name(),
                building.dimension(), building.min(), building.max(), building.level(),
                building.rotation(), farmSlots, building.createdDay(), building.type(), chosen);
        BuildingRegistry.INSTANCE.update(typed);
        player.displayClientMessage(Component.literal(
                "这座" + (typed.type() == null ? "建筑" : typed.type().label()) + "建筑已定为「"
                        + chosen.label() + "」"), true);
        sendPanel(player, typed);
    }

    private static boolean playerIn(ServerPlayer player, BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        return player.level().dimension().location().toString().equals(building.dimension())
                && player.getX() >= min.getX() - 4 && player.getX() <= max.getX() + 5
                && player.getY() >= min.getY() - 4 && player.getY() <= max.getY() + 5
                && player.getZ() >= min.getZ() - 4 && player.getZ() <= max.getZ() + 5;
    }

    private static String pageOf(BuildingMeta meta) {
        if (meta == null || meta.type() == null) {
            return "";
        }
        if (meta.work() != null) {
            switch (meta.work()) {
                case SHOP:
                    return "buildings/shop.html";
                case RESTAURANT:
                    return "buildings/restaurant.html";
                case WAREHOUSE:
                    return "buildings/warehouse.html";
                default:
                    break;
            }
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
