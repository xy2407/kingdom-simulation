package com.xy2407.kingdom.common.path;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.server.path.UnitSnapshotStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 搜索视图：按 16³ 单元懒加载快照，向 A* 提供可站/阻挡/攀爬/半格/危险查询。
 */
final class SnapshotView {

    private final UnitSnapshotStore store;
    private final String dimension;
    private final Map<Long, UnitSnapshot> units = new HashMap<>();
    private final Set<Long> missed = new HashSet<>();
    private int missingChunks;

    private SnapshotView(UnitSnapshotStore store, String dimension) {
        this.store = store;
        this.dimension = dimension;
    }

    static SnapshotView create(SnapshotRepository repository, ResourceKey<Level> dimension,
                               int minX, int maxX, int minZ, int maxZ) {
        return new SnapshotView(UnitSnapshotStore.INSTANCE, dimension.location().toString());
    }

    int missingChunks() {
        return missingChunks;
    }

    private static long key(int x, int y, int z) {
        return (((long) (x >> 4)) << 42) ^ (((long) (y >> 4)) << 21) ^ ((z >> 4) & 0x1FFFFFL);
    }

    private UnitSnapshot unitOrNull(int x, int y, int z) {
        long key = key(x, y, z);
        UnitSnapshot unit = units.get(key);
        if (unit != null) {
            return unit;
        }
        if (missed.contains(key)) {
            return null;
        }
        unit = store.get(dimension, x >> 4, y >> 4, z >> 4);
        if (unit == null) {
            missed.add(key);
            missingChunks++;
            return null;
        }
        units.put(key, unit);
        return unit;
    }

    boolean standable(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit != null && unit.standable(x & 15, y & 15, z & 15);
    }

    boolean blocked(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit == null || unit.blocked(x & 15, y & 15, z & 15);
    }

    boolean climbable(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit != null && unit.climbable(x & 15, y & 15, z & 15);
    }

    boolean halfFloor(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit != null && unit.halfFloor(x & 15, y & 15, z & 15);
    }

    int snap(BlockPos pos) {
        for (int dy = 0; dy <= NpcPathfinder.SNAP_RANGE; dy++) {
            if (standable(pos.getX(), pos.getY() - dy, pos.getZ())) {
                return pos.getY() - dy;
            }
            if (dy > 0 && standable(pos.getX(), pos.getY() + dy, pos.getZ())) {
                return pos.getY() + dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    byte standHazard(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit == null ? ChunkSnapshot.HAZARD_NONE : unit.standHazard(x & 15, y & 15, z & 15);
    }

    boolean onRoad(int x, int y, int z) {
        UnitSnapshot unit = unitOrNull(x, y, z);
        return unit != null && unit.onRoad(x & 15, y & 15, z & 15);
    }

    boolean cactusAdjacent(int x, int y, int z) {
        for (Direction direction : NpcPathfinder.HORIZONTALS) {
            int nx = x + direction.getStepX();
            int nz = z + direction.getStepZ();
            UnitSnapshot unit = unitOrNull(nx, y, nz);
            if (unit != null && unit.blockedCactus(nx & 15, y & 15, nz & 15)) {
                return true;
            }
        }
        return false;
    }
}
