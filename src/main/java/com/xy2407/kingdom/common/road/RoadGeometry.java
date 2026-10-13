package com.xy2407.kingdom.common.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * 折线的纯几何计算：连通性（有根判定）、覆盖格与按区块分组，客户端预览与服务端权威重算共用同一套。
 */
public final class RoadGeometry {

    private RoadGeometry() {
    }

    public static boolean[] computeRooted(List<RoadPolyline> lines, Set<Long> ownChunks) {
        int count = lines.size();
        boolean[] rooted = new boolean[count];
        if (count == 0 || ownChunks == null || ownChunks.isEmpty()) {
            return rooted;
        }
        Set<Long> neighborhood = new HashSet<>();
        for (long key : ownChunks) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighborhood.add(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                }
            }
        }
        for (int index = 0; index < count; index++) {
            rooted[index] = touchesChunk(lines.get(index), neighborhood);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int outer = 0; outer < count; outer++) {
                if (rooted[outer]) {
                    continue;
                }
                for (int inner = 0; inner < count; inner++) {
                    if (inner == outer || !rooted[inner]) {
                        continue;
                    }
                    if (near(lines.get(outer), lines.get(inner))) {
                        rooted[outer] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return rooted;
    }

    public static Set<Long> coveredChunks(List<RoadPolyline> lines) {
        return cellsByChunk(lines).keySet();
    }

    // 折线按 1 格步长插值后做半径圆盘膨胀，输出"区块 key → 待铺格（BlockPos.asLong，y 暂填 0）"，
    // 服务端铺路时逐格重取权威地表 y
    public static Map<Long, List<Long>> cellsByChunk(List<RoadPolyline> lines) {
        Map<Long, Set<Long>> deduped = new HashMap<>();
        int radius = RoadPolyline.RADIUS;
        double limit = (radius + 0.5) * (radius + 0.5);
        for (RoadPolyline line : lines) {
            for (int index = 1; index < line.pointCount(); index++) {
                walkSegment(deduped, line.xs()[index - 1], line.zs()[index - 1],
                        line.xs()[index], line.zs()[index], radius, limit);
            }
            if (line.pointCount() == 1) {
                stampDisc(deduped, Math.round(line.xs()[0]), Math.round(line.zs()[0]), radius, limit);
            }
        }
        Map<Long, List<Long>> result = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : deduped.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    // W9 领地内裁剪：沿折线 1 格步长走，落在领地区块内的格丢弃并断线，返回只含领地外部分的段集合
    public static List<RoadPolyline> clipToOutsideTerritory(List<RoadPolyline> lines, Set<Long> ownChunks) {
        List<RoadPolyline> result = new ArrayList<>();
        if (ownChunks == null || ownChunks.isEmpty()) {
            result.addAll(lines);
            return result;
        }
        for (RoadPolyline line : lines) {
            List<double[]> current = new ArrayList<>();
            for (int index = 0; index < line.pointCount(); index++) {
                int x = (int) Math.floor(line.xs()[index]);
                int z = (int) Math.floor(line.zs()[index]);
                if (ownChunks.contains(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))) {
                    if (current.size() > 1) {
                        result.add(RoadPolyline.of(current));
                    }
                    current.clear();
                    continue;
                }
                current.add(new double[] {x + 0.5D, z + 0.5D});
            }
            if (current.size() > 1) {
                result.add(RoadPolyline.of(current));
            }
        }
        return result;
    }

    private static void walkSegment(Map<Long, Set<Long>> result, float x0, float z0, float x1, float z1,
                                    int radius, double limit) {
        int steps = Math.max(Math.abs(Math.round(x1) - Math.round(x0)), Math.abs(Math.round(z1) - Math.round(z0)));
        steps = Math.max(1, steps);
        for (int step = 0; step <= steps; step++) {
            int centerX = Math.round(x0 + (x1 - x0) * step / steps);
            int centerZ = Math.round(z0 + (z1 - z0) * step / steps);
            stampDisc(result, centerX, centerZ, radius, limit);
        }
    }

    private static void stampDisc(Map<Long, Set<Long>> result, int centerX, int centerZ,
                                  int radius, double limit) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
                result.computeIfAbsent(chunkKey, key -> new HashSet<>())
                        .add(BlockPos.asLong(x, 0, z));
            }
        }
    }

    private static boolean touchesChunk(RoadPolyline line, Set<Long> chunks) {
        for (int index = 0; index < line.pointCount(); index++) {
            int chunkX = Math.floorDiv((int) Math.floor(line.xs()[index]), 16);
            int chunkZ = Math.floorDiv((int) Math.floor(line.zs()[index]), 16);
            if (chunks.contains(ChunkPos.asLong(chunkX, chunkZ))) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(RoadPolyline a, RoadPolyline b) {
        double limit = RoadPolyline.RADIUS * 2.0 + 1.0;
        double limitSq = limit * limit;
        for (int i = 0; i < a.pointCount(); i++) {
            for (int j = 0; j < b.pointCount(); j++) {
                double dx = a.xs()[i] - b.xs()[j];
                double dz = a.zs()[i] - b.zs()[j];
                if (dx * dx + dz * dz <= limitSq) {
                    return true;
                }
            }
        }
        return false;
    }
}
