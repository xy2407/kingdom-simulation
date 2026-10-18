package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.common.path.RoadGraph;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * 道路三段式的限深 BFS：吸附路网、验证桥接段、走到目标格。
 */
final class RoadBfs {

    record Bfs(long goal, List<Long> path) {
    }

    private RoadBfs() {
    }

    static Bfs toRoadComponent(ServerLevel level, long start, RoadGraph graph,
                               Set<Integer> components, int maxDepth) {
        if (RoadStore.INSTANCE.roadCells(graph.dimension()).contains(start)
                && components.contains(componentOf(graph, start))) {
            return new Bfs(start, List.of(start));
        }
        Map<Long, Integer> depth = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        List<Long> queue = new ArrayList<>();
        depth.put(start, 0);
        queue.add(start);
        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            int d = depth.get(current);
            if (d >= maxDepth) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = BlockPos.asLong(pos.getX() + direction.getStepX(),
                            pos.getY() + dy, pos.getZ() + direction.getStepZ());
                    if (depth.containsKey(next) || !isStandable(level, next)) {
                        continue;
                    }
                    depth.put(next, d + 1);
                    cameFrom.put(next, current);
                    if (RoadStore.INSTANCE.roadCells(graph.dimension()).contains(next)
                            && components.contains(componentOf(graph, next))) {
                        return new Bfs(next, trace(cameFrom, start, next));
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    static Bfs toGoal(ServerLevel level, long start, long goal, int maxDepth) {
        if (start == goal) {
            return new Bfs(goal, List.of(start));
        }
        Map<Long, Integer> depth = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        List<Long> queue = new ArrayList<>();
        depth.put(start, 0);
        queue.add(start);
        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            int d = depth.get(current);
            if (d >= maxDepth) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = BlockPos.asLong(pos.getX() + direction.getStepX(),
                            pos.getY() + dy, pos.getZ() + direction.getStepZ());
                    if (next == goal) {
                        cameFrom.put(next, current);
                        return new Bfs(goal, trace(cameFrom, start, goal));
                    }
                    if (depth.containsKey(next) || !isStandable(level, next)) {
                        continue;
                    }
                    depth.put(next, d + 1);
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    static Bfs toAnyRoad(ServerLevel level, long start, RoadGraph graph, int maxDepth) {
        if (RoadStore.INSTANCE.roadCells(graph.dimension()).contains(start)) {
            return new Bfs(start, List.of(start));
        }
        Map<Long, Integer> depth = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        List<Long> queue = new ArrayList<>();
        depth.put(start, 0);
        queue.add(start);
        int head = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            int d = depth.get(current);
            if (d >= maxDepth) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = BlockPos.asLong(pos.getX() + direction.getStepX(),
                            pos.getY() + dy, pos.getZ() + direction.getStepZ());
                    if (depth.containsKey(next) || !isStandable(level, next)) {
                        continue;
                    }
                    depth.put(next, d + 1);
                    cameFrom.put(next, current);
                    if (RoadStore.INSTANCE.roadCells(graph.dimension()).contains(next)) {
                        return new Bfs(next, trace(cameFrom, start, next));
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    static int componentOf(RoadGraph graph, long pos) {
        int node = graph.nodeAt(pos);
        return node < 0 ? -1 : graph.componentOf(node);
    }

    static List<Long> trace(Map<Long, Long> cameFrom, long start, long goal) {
        List<Long> path = new ArrayList<>();
        long cursor = goal;
        path.add(cursor);
        while (cursor != start) {
            Long prev = cameFrom.get(cursor);
            if (prev == null) {
                return List.of();
            }
            cursor = prev;
            path.add(cursor);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    static boolean isStandable(ServerLevel level, long pos) {
        BlockPos blockPos = BlockPos.of(pos);
        int x = blockPos.getX();
        int y = blockPos.getY();
        int z = blockPos.getZ();
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
