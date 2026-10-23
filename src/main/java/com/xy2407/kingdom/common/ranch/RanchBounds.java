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
 * 养殖栏的空间解析：包围盒、可站立点与建筑内容器，支持按建筑或按自定义范围查询
 */
public final class RanchBounds {

    private static final int MAX_SCAN = 32768;

    private static final Map<String, List<BlockPos>> POINTS = new ConcurrentHashMap<>();

    private RanchBounds() {
    }

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

    public static List<BlockPos> standablePoints(ServerLevel level, BuildingRecord building) {
        return standablePoints(level, box(building), "b:" + building.id());
    }

    public static List<BlockPos> standablePoints(ServerLevel level, AABB area, String cacheKey) {
        String key = cacheKey == null ? "area:" + area.hashCode() : cacheKey;
        List<BlockPos> cached = POINTS.get(key);
        if (cached != null) {
            return cached;
        }
        BlockPos low = BlockPos.containing(area.minX, area.minY, area.minZ);
        BlockPos high = BlockPos.containing(area.maxX - 1.0D, area.maxY - 1.0D, area.maxZ - 1.0D);
        List<BlockPos> points = new ArrayList<>();
        int scanned = 0;
        for (int y = low.getY(); y <= high.getY() && scanned < MAX_SCAN; y++) {
            for (int x = low.getX(); x <= high.getX() && scanned < MAX_SCAN; x++) {
                for (int z = low.getZ(); z <= high.getZ() && scanned < MAX_SCAN; z++) {
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
            points.add(BlockPos.containing(area.getCenter()).above());
        }
        List<BlockPos> result = List.copyOf(points);
        POINTS.put(key, result);
        return result;
    }

    public static List<Container> containers(ServerLevel level, BuildingRecord building) {
        return containers(level, box(building));
    }

    public static List<Container> containers(ServerLevel level, AABB area) {
        List<Container> found = new ArrayList<>();
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
        POINTS.remove("b:" + buildingId);
    }

    public static void invalidate(String cacheKey) {
        POINTS.remove(cacheKey);
    }

    public static void reset() {
        POINTS.clear();
    }
}
