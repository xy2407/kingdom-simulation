package com.xy2407.kingdom.server.area;

import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.IndustryKind;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.network.area.AreaStatePacket;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 作业范围服务端：提交框选（按最近建筑兜底归属）与回发已有范围。
 */
public final class PlotAreaService {

    private PlotAreaService() {
    }

    public static void submit(ServerPlayer player, PlotAreaKind kind, long[] cells) {
        if (player == null || kind == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BuildingRecord building = nearestBuilding(level, kind, player.getBlockX(), player.getBlockZ());
        if (building == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "附近没有可用的" + (kind == PlotAreaKind.LUMBER ? "伐木场" : "养殖场")
                            + "建筑，先把建筑放下再框选"), false);
            return;
        }
        List<Long> normalized = PlotArea.normalize(cells);
        if (normalized.isEmpty()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "范围为空，请至少刷到一格地面"), false);
            return;
        }
        PlotAreaManager.INSTANCE.upsert(building.id(), kind,
                level.dimension().location().toString(), normalized,
                DateSystem.totalDay(level));
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                kind.label() + "已保存：" + normalized.size() + " 格"), false);
        respond(player, kind);
    }

    public static void respond(ServerPlayer player, PlotAreaKind kind) {
        if (player == null || kind == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        List<PlotArea> areas = PlotAreaManager.INSTANCE.all(dimension, kind);
        UUID[] buildingIds = new UUID[areas.size()];
        long[][] cells = new long[areas.size()][];
        for (int index = 0; index < areas.size(); index++) {
            PlotArea area = areas.get(index);
            buildingIds[index] = area.buildingId();
            cells[index] = area.toArray();
        }
        PacketDistributor.sendToPlayer(player, new AreaStatePacket(kind, buildingIds, cells));
    }

    private static BuildingRecord nearestBuilding(ServerLevel level, PlotAreaKind kind,
                                                  int x, int z) {
        String dimension = level.dimension().location().toString();
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension())) {
                continue;
            }
            if (!matches(building, kind)) {
                continue;
            }
            double cx = (building.min().getX() + building.max().getX()) / 2.0D;
            double cz = (building.min().getZ() + building.max().getZ()) / 2.0D;
            double distance = (cx - x) * (cx - x) + (cz - z) * (cz - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = building;
            }
        }
        return best;
    }

    private static boolean matches(BuildingRecord building, PlotAreaKind kind) {
        var meta = BuildingRegistry.INSTANCE.metaOf(building);
        if (meta == null) {
            return false;
        }
        if (kind == PlotAreaKind.RANCH) {
            return meta.type() == BuildingType.LIVESTOCK;
        }
        if (meta.type() != BuildingType.INDUSTRY) {
            return false;
        }
        return meta.industryKind() == IndustryKind.LUMBER;
    }

}