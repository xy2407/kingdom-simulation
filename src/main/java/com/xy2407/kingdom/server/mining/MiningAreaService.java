package com.xy2407.kingdom.server.mining;

import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.IndustryKind;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 采矿笔刷提交：按刷到的格集取包围盒与每列最高方块，创建矿坑或按新范围重开。
 */
public final class MiningAreaService {

    private MiningAreaService() {
    }

    public static void submit(ServerPlayer player, long[] cells, UUID buildingId) {
        if (player == null || cells == null || cells.length == 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BuildingRecord building = buildingId == null ? nearestMiningBuilding(level, player.blockPosition())
                : miningBuilding(level, buildingId);
        if (building == null) {
            player.displayClientMessage(Component.literal(
                    "附近没有采矿分类的工业建筑（蓝图 json 填 industry_kind=采矿 并完工）"), false);
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int surfaceY = Integer.MIN_VALUE;
        for (long cell : cells) {
            BlockPos pos = BlockPos.of(cell);
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            surfaceY = Math.max(surfaceY, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                    pos.getX(), pos.getZ()));
        }
        if (surfaceY <= level.getMinBuildHeight()) {
            player.displayClientMessage(Component.literal("笔刷范围内没有可开采的地表"), false);
            return;
        }
        BlockPos min = new BlockPos(minX, level.getMinBuildHeight(), minZ);
        BlockPos max = new BlockPos(maxX, surfaceY, maxZ);
        List<Long> normalizedCells = MiningPlot.normalize(cells);
        MiningPlot existing = MiningPlotManager.INSTANCE.byBuilding(building.id());
        if (existing != null) {
            MiningPlotManager.INSTANCE.update(existing.withArea(min, max, surfaceY, normalizedCells));
        } else {
            NpcData worker = nearestCitizen(level, player.blockPosition());
            MiningPlotManager.INSTANCE.create(building, "矿场", min, max, surfaceY, List.of(),
                    DateSystem.totalDay(level), normalizedCells);
            if (worker != null) {
                MiningPlot plot = MiningPlotManager.INSTANCE.byBuilding(building.id());
                if (plot != null) {
                    com.xy2407.kingdom.server.employment.EmploymentService.INSTANCE.hire(
                            level.getServer(), worker.getId(),
                            com.xy2407.kingdom.common.employment.EmploymentTarget.mining(
                                    building.id(), plot.id()));
                }
            }
        }
        player.displayClientMessage(Component.literal("矿坑已划定："
                + normalizedCells.size() + " 格"
                + "　起始层 y=" + (surfaceY - 1)
                + "　建筑=" + building.name()), false);
    }

    private static BuildingRecord miningBuilding(ServerLevel level, UUID buildingId) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(buildingId);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.industryKind() == IndustryKind.MINING ? building : null;
    }

    public static BuildingRecord nearestMiningBuilding(ServerLevel level, BlockPos from) {
        String dimension = level.dimension().location().toString();
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension())) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta == null || meta.industryKind() != IndustryKind.MINING) {
                continue;
            }
            double distance = building.min().distSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = building;
            }
        }
        return best;
    }

    private static NpcData nearestCitizen(ServerLevel level, BlockPos from) {
        String dimension = level.dimension().location().toString();
        NpcData best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            double dx = npc.getX() - from.getX();
            double dy = npc.getY() - from.getY();
            double dz = npc.getZ() - from.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = npc;
            }
        }
        return best;
    }
}
