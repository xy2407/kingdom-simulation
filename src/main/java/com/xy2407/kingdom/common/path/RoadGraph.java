package com.xy2407.kingdom.common.path;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 领地道路的只读路图快照（P1）：路格压成紧凑节点 id + CSR 邻接 + 连通分量；
 * 邻接规则与主 A* 一致（4 方向水平、相邻列 y 差 ±1 内可站、代价同 stepCost 式，无对角）。
 * 不可变整体替换引用，A* 线程可安全读；构建只在服务端线程（RoadGraphService）。
 */
public final class RoadGraph {

    // 与 NpcPathfinder 的 stepCost 系数一致（平 1.0 / 上 1+1.1·dy / 下 1+1.2·|dy|）
    public static final double COST_STEP_UP = 1.1D;

    public static final double COST_STEP_DOWN = 1.2D;

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

    private RoadGraph(String dimension, long[] positions, Long2IntOpenHashMap nodeIds,
                      int[] components, int componentCount,
                      int[] neighborStarts, int[] neighborTargets, float[] neighborCosts) {
        this.dimension = dimension;
        this.positions = positions;
        this.nodeIds = nodeIds;
        this.components = components;
        this.componentCount = componentCount;
        this.neighborStarts = neighborStarts;
        this.neighborTargets = neighborTargets;
        this.neighborCosts = neighborCosts;
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

    // 精确坐标匹配（含 y）；P3 的"附近路格"查询会在此之上加按 xz 桶的空间索引
    public int nodeAt(long pos) {
        return nodeIds.getOrDefault(pos, -1);
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
                // 相邻列的 y-1 / y / y+1 三层各连一条边（与主 A* 的 step-up/drop 语义一致）
                for (int dy = -1; dy <= 1; dy++) {
                    int neighbor = nodeIds.getOrDefault(BlockPos.asLong(nx, pos.getY() + dy, nz), -1);
                    if (neighbor < 0) {
                        continue;
                    }
                    double cost = 1.0D;
                    if (dy > 0) {
                        cost = 1.0D + COST_STEP_UP * dy;
                    } else if (dy < 0) {
                        cost = 1.0D + COST_STEP_DOWN * (-dy);
                    }
                    neighborTargets[cursor[node]] = neighbor;
                    neighborCosts[cursor[node]] = (float) cost;
                    cursor[node]++;
                }
            }
        }

        // BFS 染色连通分量
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
                neighborStarts, neighborTargets, neighborCosts);
    }

    private static int countNeighbors(Long2IntOpenHashMap nodeIds, long pos) {
        BlockPos blockPos = BlockPos.of(pos);
        int total = 0;
        for (Direction direction : HORIZONTALS) {
            int nx = blockPos.getX() + direction.getStepX();
            int nz = blockPos.getZ() + direction.getStepZ();
            for (int dy = -1; dy <= 1; dy++) {
                if (nodeIds.containsKey(BlockPos.asLong(nx, blockPos.getY() + dy, nz))) {
                    total++;
                }
            }
        }
        return total;
    }
}
