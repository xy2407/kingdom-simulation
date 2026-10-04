package com.xy2407.kingdom.common.ranch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.BuildingRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * 养殖栏的空间解析：包围盒、可站立点与建筑内容器，全部由建筑登记表推导
 */
public final class RanchBounds {

    private static final int MAX_SCAN = 32768;
    private static final Map<UUID, List<BlockPos>> POINTS = new ConcurrentHashMap<>();

    private RanchBounds() {
    }

    /** 建筑包围盒，纵向多留两格给动物身高（对齐参照实现的 buildingBounds）。 */
    public static AABB box(BuildingRecord building) {
        BlockPos min = building.min();
        BlockPos max = building.max();
        return new AABB(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ()),
                Math.max(min.getX(), max.getX()) + 1.0D,
                Math.max(min.getY(), max.getY()) + 2.0D,
                Math.max(min.getZ(), max.getZ()) + 1.0D);
    }

    public static BlockPos center(BuildingRecord building) {
        return new BlockPos(
                (building.min().getX() + building.max().getX()) / 2,
                building.min().getY(),
                (building.min().getZ() + building.max().getZ()) / 2);
    }

    /** 栏内可站立点：脚下实心 + 本体与头顶空气；结果按坐标顺序缓存。 */
    public static List<BlockPos> standablePoints(ServerLevel level, BuildingRecord building) {
        List<BlockPos> cached = POINTS.get(building.id());
        if (cached != null) {
            return cached;
        }
        List<BlockPos> points = new ArrayList<>();
        int scanned = 0;
        for (int y = building.min().getY() + 1; y <= building.max().getY() + 1 && scanned < MAX_SCAN; y++) {
            for (int x = building.min().getX(); x <= building.max().getX() && scanned < MAX_SCAN; x++) {
                for (int z = building.min().getZ(); z <= building.max().getZ() && scanned < MAX_SCAN; z++) {
                    scanned++;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    BlockPos below = pos.below();
                    if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                        continue;
                    }
                    points.add(pos.immutable());
                }
            }
        }
        if (points.isEmpty()) {
            points.add(center(building).above());
        }
        List<BlockPos> result = List.copyOf(points);
        POINTS.put(building.id(), result);
        return result;
    }

    /** 建筑范围内已加载的容器，用作饲料箱与产出点；没有容器时相关流程自动跳过。 */
    public static List<Container> containers(ServerLevel level, BuildingRecord building) {
        List<Container> found = new ArrayList<>();
        AABB area = box(building);
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX - 1.0D, area.maxY - 1.0D, area.maxZ - 1.0D))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                found.add(container);
            }
        }
        return found;
    }

    public static void invalidate(UUID buildingId) {
        POINTS.remove(buildingId);
    }

    public static void reset() {
        POINTS.clear();
    }
}
