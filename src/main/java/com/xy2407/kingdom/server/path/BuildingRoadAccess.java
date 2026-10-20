package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * 建筑接入点的计算与缓存（P2）：多源限深 BFS 从建筑外圈走到道路格；
 * 内存态不持久化（20 格 BFS 毫秒级，道路/建筑变动即重算，比落库+搬迁平移更简单可靠，D4 已定）。
 * 懒算：首次 accessOf 才算；失效：道路变更全清、建筑登记/更新单清。
 */
public final class BuildingRoadAccess {

    private static final int MAX_SEARCH_DEPTH = 20;

    private static final int MAX_ACCESS_CELLS = 8;

    private static final int FLOOR_SCAN_BELOW = 2;

    private static final int FLOOR_SCAN_ABOVE = 4;

    private static final Map<UUID, List<AccessCell>> CACHE = new HashMap<>();

    private static final Set<UUID> EMPTY_BUILDINGS = new HashSet<>();

    private BuildingRoadAccess() {
    }

    public static void markBuildingDirty(UUID buildingId) {
        CACHE.remove(buildingId);
        EMPTY_BUILDINGS.remove(buildingId);
    }

    public static void markRoadDirty() {
        CACHE.clear();
        EMPTY_BUILDINGS.clear();
    }

    public static void reset() {
        CACHE.clear();
        EMPTY_BUILDINGS.clear();
    }

    public static List<AccessCell> accessOf(ServerLevel level, BuildingRecord building) {
        if (level == null || building == null) {
            return List.of();
        }
        List<AccessCell> cached = CACHE.get(building.id());
        if (cached != null) {
            return cached;
        }
        if (EMPTY_BUILDINGS.contains(building.id())) {
            return List.of();
        }
        List<AccessCell> cells = compute(level, building);
        if (cells.isEmpty()) {
            EMPTY_BUILDINGS.add(building.id());
            return List.of();
        }
        CACHE.put(building.id(), cells);
        return cells;
    }

    private static List<AccessCell> compute(ServerLevel level, BuildingRecord building) {
        if (!building.dimension().equals(level.dimension().location().toString())) {
            return List.of();
        }
        Set<Long> roadCells = RoadStore.INSTANCE.roadCells(building.dimension());
        if (roadCells.isEmpty()) {
            return List.of();
        }
        List<Long> starts = startCells(level, building);
        if (starts.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> visited = new HashMap<>();
        List<Long> queue = new ArrayList<>();
        for (long start : starts) {
            visited.put(start, 0);
            queue.add(start);
        }
        List<AccessCell> result = new ArrayList<>();
        int head = 0;
        while (head < queue.size() && result.size() < MAX_ACCESS_CELLS) {
            long current = queue.get(head++);
            int depth = visited.get(current);
            if (roadCells.contains(current)) {
                result.add(new AccessCell(current, depth));
                continue;
            }
            if (depth >= MAX_SEARCH_DEPTH) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = pos.getX() + direction.getStepX();
                int nz = pos.getZ() + direction.getStepZ();
                for (int dy = -1; dy <= 1; dy++) {
                    long next = BlockPos.asLong(nx, pos.getY() + dy, nz);
                    if (visited.containsKey(next)) {
                        continue;
                    }
                    BlockPos nextPos = BlockPos.of(next);
                    if (!level.isLoaded(nextPos)) {
                        continue;
                    }
                    if (!isStandable(level, nx, nextPos.getY(), nz)) {
                        continue;
                    }
                    visited.put(next, depth + 1);
                    queue.add(next);
                }
            }
        }
        return result;
    }

    private static List<Long> startCells(ServerLevel level, BuildingRecord building) {
        List<Long> starts = new ArrayList<>();
        int minX = building.min().getX() - 1;
        int maxX = building.max().getX() + 1;
        int minZ = building.min().getZ() - 1;
        int maxZ = building.max().getZ() + 1;
        int yMin = building.min().getY() - FLOOR_SCAN_BELOW;
        int yMax = building.max().getY() + FLOOR_SCAN_ABOVE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!edge) {
                    continue;
                }
                for (int y = yMin; y <= yMax; y++) {
                    if (isStandable(level, x, y, z)) {
                        starts.add(BlockPos.asLong(x, y, z));
                        break;
                    }
                }
            }
        }
        return starts;
    }

    private static boolean isStandable(ServerLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(x, y, z);
        if (!ChunkSnapshot.isWalkableFloor(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 1, z);
        if (!ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 2, z);
        return ChunkSnapshot.isPassable(level.getBlockState(cursor));
    }
}
