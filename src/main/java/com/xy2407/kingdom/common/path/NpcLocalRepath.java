package com.xy2407.kingdom.common.path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 卡住后的局部重接：16 格内限深 BFS 撞回原路径航点，成功则把原路径剩余段接在尾部。
 */
public final class NpcLocalRepath {

    private static final int MAX_DEPTH = 16;
    private static final int MIN_DEPTH = 4;
    private static final int DEPTH_SLACK = 2;
    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_DISTANCE_SQR = MAX_DEPTH * MAX_DEPTH;

    private NpcLocalRepath() {
    }

    public static List<NpcPathfinder.Waypoint> findPath(Level level, BlockPos from, BlockPos to,
                                                        int maxDepth) {
        if (level == null || from == null || to == null) {
            return null;
        }
        long start = from.asLong();
        long goal = to.asLong();
        if (start == goal) {
            return null;
        }
        int limit = Math.max(2, Math.min(MAX_DEPTH, maxDepth));
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Integer> depth = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        cameFrom.put(start, start);
        depth.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int currentDepth = depth.get(current);
            if (currentDepth >= limit) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : NpcPathfinder.HORIZONTALS) {
                int nx = pos.getX() + direction.getStepX();
                int nz = pos.getZ() + direction.getStepZ();
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = pos.getY() + dy;
                    long next = BlockPos.asLong(nx, ny, nz);
                    if (depth.containsKey(next) || !isStandable(level, nx, ny, nz, dy > 0)) {
                        continue;
                    }
                    cameFrom.put(next, current);
                    depth.put(next, currentDepth + 1);
                    if (next == goal) {
                        return waypoints(trace(cameFrom, start, next));
                    }
                    queue.add(next);
                }
            }
        }
        long best = -1L;
        int bestDistance = Integer.MAX_VALUE;
        for (long visited : depth.keySet()) {
            BlockPos pos = BlockPos.of(visited);
            int distance = Math.abs(pos.getX() - to.getX())
                    + Math.abs(pos.getY() - to.getY())
                    + Math.abs(pos.getZ() - to.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = visited;
            }
        }
        return best < 0L ? null : waypoints(trace(cameFrom, start, best));
    }

    private static List<NpcPathfinder.Waypoint> waypoints(List<BlockPos> points) {
        List<NpcPathfinder.Waypoint> out = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            out.add(new NpcPathfinder.Waypoint(points.get(i), moveOf(points, i)));
        }
        return out.size() < 2 ? null : out;
    }

    public static List<NpcPathfinder.Waypoint> reconnect(Level level, BlockPos from,
                                                         List<NpcPathfinder.Waypoint> path, int index) {
        if (level == null || path == null || from == null || index < 0 || index >= path.size()) {
            return null;
        }
        Map<Long, Integer> candidates = new HashMap<>();
        for (int i = index; i < path.size() && candidates.size() < MAX_CANDIDATES; i++) {
            BlockPos pos = path.get(i).pos();
            if (pos.distSqr(from) > MAX_DISTANCE_SQR) {
                continue;
            }
            candidates.putIfAbsent(pos.asLong(), i);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        long start = from.asLong();
        Integer here = candidates.get(start);
        if (here != null) {
            return tail(path, here, List.of());
        }
        int nearest = MAX_DEPTH;
        for (int i = index; i < path.size(); i++) {
            BlockPos pos = path.get(i).pos();
            int chebyshev = Math.max(Math.abs(pos.getX() - from.getX()),
                    Math.abs(pos.getZ() - from.getZ())) + Math.abs(pos.getY() - from.getY());
            nearest = Math.min(nearest, chebyshev);
        }
        int limit = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, nearest + DEPTH_SLACK));
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Integer> depth = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        cameFrom.put(start, start);
        depth.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int currentDepth = depth.get(current);
            if (currentDepth >= limit) {
                continue;
            }
            BlockPos pos = BlockPos.of(current);
            for (Direction direction : NpcPathfinder.HORIZONTALS) {
                int nx = pos.getX() + direction.getStepX();
                int nz = pos.getZ() + direction.getStepZ();
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = pos.getY() + dy;
                    long next = BlockPos.asLong(nx, ny, nz);
                    if (depth.containsKey(next) || !isStandable(level, nx, ny, nz, dy > 0)) {
                        continue;
                    }
                    cameFrom.put(next, current);
                    depth.put(next, currentDepth + 1);
                    Integer hit = candidates.get(next);
                    if (hit != null) {
                        return tail(path, hit, trace(cameFrom, start, next));
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static List<NpcPathfinder.Waypoint> tail(List<NpcPathfinder.Waypoint> path, int index,
                                                     List<BlockPos> prefix) {
        List<NpcPathfinder.Waypoint> out = new ArrayList<>(prefix.size() + path.size() - index);
        for (int i = 0; i < prefix.size(); i++) {
            out.add(new NpcPathfinder.Waypoint(prefix.get(i), moveOf(prefix, i)));
        }
        for (int i = index + 1; i < path.size(); i++) {
            out.add(path.get(i));
        }
        return out.size() < 2 ? null : out;
    }

    private static NpcPathfinder.Waypoint.Move moveOf(List<BlockPos> prefix, int index) {
        if (index <= 0) {
            return NpcPathfinder.Waypoint.Move.WALK;
        }
        int dy = prefix.get(index).getY() - prefix.get(index - 1).getY();
        if (dy > 0) {
            return NpcPathfinder.Waypoint.Move.STEP_UP;
        }
        if (dy < 0) {
            return NpcPathfinder.Waypoint.Move.DROP;
        }
        return NpcPathfinder.Waypoint.Move.WALK;
    }

    private static List<BlockPos> trace(Map<Long, Long> cameFrom, long start, long goal) {
        List<BlockPos> path = new ArrayList<>();
        long cursor = goal;
        while (true) {
            path.add(BlockPos.of(cursor));
            if (cursor == start) {
                break;
            }
            Long previous = cameFrom.get(cursor);
            if (previous == null) {
                return List.of();
            }
            cursor = previous;
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean isStandable(Level level, int x, int y, int z, boolean ascending) {
        if (y < level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(x, y - 1, z);
        if (!level.isLoaded(cursor) || !ChunkSnapshot.isWalkableFloor(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y, z);
        if (!ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 1, z);
        if (!ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
            return false;
        }
        if (!ascending) {
            return true;
        }
        cursor.set(x, y + 2, z);
        return ChunkSnapshot.isPassable(level.getBlockState(cursor));
    }
}
