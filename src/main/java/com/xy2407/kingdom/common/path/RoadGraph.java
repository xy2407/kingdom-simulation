package com.xy2407.kingdom.common.path;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 领地道路的只读路图快照（P1）：路格压成紧凑节点 id + CSR 邻接 + 连通分量；
 * 邻接规则与主 A* 一致（4 方向水平、相邻列 y 差 ±1 内可站、代价同 stepCost 式，无对角）。
 * 不可变整体替换引用，A* 线程可安全读；构建只在服务端线程（RoadGraphService）。
 */
public final class RoadGraph {

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private final String dimension;

    private final long[] positions;

    private final Long2IntOpenHashMap nodeIds;

    private final int[] components;

    private final int componentCount;

    private final int[] neighborStarts;

    private final int[] neighborTargets;

    private final float[] neighborCosts;

    private final Long2ObjectOpenHashMap<int[]> columns;

    private RoadGraph(String dimension, long[] positions, Long2IntOpenHashMap nodeIds,
                      int[] components, int componentCount,
                      int[] neighborStarts, int[] neighborTargets, float[] neighborCosts,
                      Long2ObjectOpenHashMap<int[]> columns) {
        this.dimension = dimension;
        this.positions = positions;
        this.nodeIds = nodeIds;
        this.components = components;
        this.componentCount = componentCount;
        this.neighborStarts = neighborStarts;
        this.neighborTargets = neighborTargets;
        this.neighborCosts = neighborCosts;
        this.columns = columns;
    }

    public String dimension() {
        return dimension;
    }

    public int nodeCount() {
        return positions.length;
    }

    public int componentCount() {
        return componentCount;
    }

    public long positionOf(int node) {
        return positions[node];
    }

    public int componentOf(int node) {
        return components[node];
    }

    public int nodeAt(long pos) {
        return nodeIds.getOrDefault(pos, -1);
    }

    public int nearest(int x, int y, int z, int radius) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int[] bucket = columns.get(columnKey(x + dx, z + dz));
                if (bucket == null) {
                    continue;
                }
                for (int node : bucket) {
                    BlockPos pos = BlockPos.of(positions[node]);
                    double dy = pos.getY() - y;
                    double dist = (double) dx * dx + (double) dz * dz + dy * dy * 4.0D;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = node;
                    }
                }
            }
        }
        return best;
    }

    public void collectInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, IntConsumer sink) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int[] bucket = columns.get(columnKey(x, z));
                if (bucket == null) {
                    continue;
                }
                for (int node : bucket) {
                    int py = BlockPos.of(positions[node]).getY();
                    if (py >= minY && py <= maxY) {
                        sink.accept(node);
                    }
                }
            }
        }
    }

    public int neighborCount(int node) {
        return neighborStarts[node + 1] - neighborStarts[node];
    }

    public int neighborTarget(int node, int index) {
        return neighborTargets[neighborStarts[node] + index];
    }

    public float neighborCost(int node, int index) {
        return neighborCosts[neighborStarts[node] + index];
    }

    public static RoadGraph build(String dimension, Collection<Long> cells) {
        List<Long> sorted = new ArrayList<>(cells);
        sorted.sort(Long::compare);
        int count = sorted.size();
        long[] positions = new long[count];
        Long2IntOpenHashMap nodeIds = new Long2IntOpenHashMap(count);
        for (int index = 0; index < count; index++) {
            positions[index] = sorted.get(index);
            nodeIds.put(positions[index], index);
        }

        int[] degree = new int[count];
        for (int node = 0; node < count; node++) {
            degree[node] = countNeighbors(nodeIds, positions[node]);
        }
        int[] neighborStarts = new int[count + 1];
        for (int node = 0; node < count; node++) {
            neighborStarts[node + 1] = neighborStarts[node] + degree[node];
        }
        int[] neighborTargets = new int[neighborStarts[count]];
        float[] neighborCosts = new float[neighborStarts[count]];
        int[] cursor = new int[count];
        System.arraycopy(neighborStarts, 0, cursor, 0, count);
        for (int node = 0; node < count; node++) {
            BlockPos pos = BlockPos.of(positions[node]);
            for (Direction direction : HORIZONTALS) {
                int nx = pos.getX() + direction.getStepX();
                int nz = pos.getZ() + direction.getStepZ();
                for (int dy = -PathCostModel.MAX_DROP; dy <= PathCostModel.MAX_STEP_UP; dy++) {
                    int neighbor = nodeIds.getOrDefault(BlockPos.asLong(nx, pos.getY() + dy, nz), -1);
                    if (neighbor < 0) {
                        continue;
                    }
                    neighborTargets[cursor[node]] = neighbor;
                    neighborCosts[cursor[node]] = (float) PathCostModel.crossCost(dy);
                    cursor[node]++;
                }
            }
            for (int dy = -1; dy <= 1; dy += 2) {
                int neighbor = nodeIds.getOrDefault(
                        BlockPos.asLong(pos.getX(), pos.getY() + dy, pos.getZ()), -1);
                if (neighbor < 0) {
                    continue;
                }
                neighborTargets[cursor[node]] = neighbor;
                neighborCosts[cursor[node]] = 1.0F;
                cursor[node]++;
            }
        }

        int[] components = new int[count];
        Arrays.fill(components, -1);
        int componentCount = 0;
        int[] queue = new int[count];
        for (int seed = 0; seed < count; seed++) {
            if (components[seed] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            components[seed] = componentCount;
            while (head < tail) {
                int node = queue[head++];
                for (int edge = neighborStarts[node]; edge < neighborStarts[node + 1]; edge++) {
                    int next = neighborTargets[edge];
                    if (components[next] < 0) {
                        components[next] = componentCount;
                        queue[tail++] = next;
                    }
                }
            }
            componentCount++;
        }

        return new RoadGraph(dimension, positions, nodeIds, components, componentCount,
                neighborStarts, neighborTargets, neighborCosts, buildColumns(positions, count));
    }

    private static Long2ObjectOpenHashMap<int[]> buildColumns(long[] positions, int count) {
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int node = 0; node < count; node++) {
            BlockPos pos = BlockPos.of(positions[node]);
            buckets.computeIfAbsent(columnKey(pos.getX(), pos.getZ()), key -> new ArrayList<>()).add(node);
        }
        Long2ObjectOpenHashMap<int[]> columns = new Long2ObjectOpenHashMap<>(buckets.size());
        for (Map.Entry<Long, List<Integer>> entry : buckets.entrySet()) {
            List<Integer> ids = entry.getValue();
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ids.get(i);
            }
            columns.put(entry.getKey().longValue(), arr);
        }
        return columns;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int countNeighbors(Long2IntOpenHashMap nodeIds, long pos) {
        BlockPos blockPos = BlockPos.of(pos);
        int total = 0;
        for (Direction direction : HORIZONTALS) {
            int nx = blockPos.getX() + direction.getStepX();
            int nz = blockPos.getZ() + direction.getStepZ();
            for (int dy = -PathCostModel.MAX_DROP; dy <= PathCostModel.MAX_STEP_UP; dy++) {
                if (nodeIds.containsKey(BlockPos.asLong(nx, blockPos.getY() + dy, nz))) {
                    total++;
                }
            }
        }
        for (int dy = -1; dy <= 1; dy += 2) {
            if (nodeIds.containsKey(BlockPos.asLong(blockPos.getX(), blockPos.getY() + dy, blockPos.getZ()))) {
                total++;
            }
        }
        return total;
    }
}
