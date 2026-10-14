package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.RoadGraph;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 道路路由（P3）：三段式 = ① NPC→接入格（限深 BFS）+ ② 路网 Dijkstra + ③ 接入格→建筑目标（限深 BFS），
 * 拼成 Waypoint 列表直接 applyPath。
 * 工程折中：①③ 用主线程实时 BFS（限深 32/20，毫秒级）而非异步快照 A*——免 Future 编排；
 * 目的地不是建筑 / 两端不同分量 / 任何一段失败 → 返回 null 回退原异步寻路。
 */
public final class RoadRouteService {

    private static final int ENTRY_RADIUS = 32;

    private static final int BUILDING_DEPTH = 20;

    private RoadRouteService() {
    }

    public static List<NpcPathfinder.Waypoint> tryPlan(ServerLevel level, BlockPos from, BlockPos stand) {
        MinecraftServer server = level.getServer();
        String dimension = level.dimension().location().toString();
        BuildingRecord destination = buildingAt(dimension, stand);
        if (destination == null) {
            return null;
        }
        RoadGraph graph = RoadGraphService.current(server, dimension);
        if (graph == null || graph.nodeCount() == 0) {
            return null;
        }
        List<AccessCell> destAccess = BuildingRoadAccess.accessOf(level, destination);
        if (destAccess.isEmpty()) {
            return null;
        }
        Set<Integer> destComponents = new HashSet<>();
        for (AccessCell cell : destAccess) {
            int node = graph.nodeAt(cell.roadPos());
            if (node >= 0) {
                destComponents.add(graph.componentOf(node));
            }
        }
        if (destComponents.isEmpty()) {
            return null;
        }
        long fromGround = BlockPos.asLong(from.getX(), from.getY() - 1, from.getZ());
        long standGround = BlockPos.asLong(stand.getX(), stand.getY() - 1, stand.getZ());
        BfsResult entry = bfsToRoadComponent(level, fromGround, graph, destComponents, ENTRY_RADIUS);
        if (entry == null) {
            return null;
        }
        int entryNode = graph.nodeAt(entry.goal);
        if (entryNode < 0) {
            return null;
        }
        // ② 优先走目的地前驱场（档 B 查表），场不可用回退每趟 Dijkstra（档 A）
        List<Long> roadPath = walkField(graph, entryNode, RoadFieldCache.nextField(level, destination, graph));
        DijkstraResult crossing;
        if (roadPath != null) {
            crossing = new DijkstraResult(roadPath.get(roadPath.size() - 1), roadPath);
        } else {
            crossing = dijkstraToAccess(graph, entryNode, destAccess);
        }
        if (crossing == null) {
            return null;
        }
        BfsResult exit = bfsToGoal(level, crossing.goal, standGround, BUILDING_DEPTH);
        if (exit == null) {
            return null;
        }
        return stitch(graph, entry, crossing, exit, from, stand);
    }

    // 档 B 走表：沿 next 链从接入格走到目的地接入格（多源场的 next 指向最近的那个）；
    // 场为 null 或链异常（步数超界）返回 null，由调用方回退档 A
    private static List<Long> walkField(RoadGraph graph, int entryNode, int[] next) {
        if (next == null || entryNode < 0 || entryNode >= next.length) {
            return null;
        }
        List<Long> path = new ArrayList<>();
        int cursor = entryNode;
        int guard = graph.nodeCount() + 1;
        while (guard-- > 0) {
            path.add(graph.positionOf(cursor));
            int hop = next[cursor];
            if (hop < 0) {
                return path;
            }
            cursor = hop;
        }
        return null;
    }

    private static BuildingRecord buildingAt(String dimension, BlockPos stand) {
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (record.contains(dimension, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D)) {
                return record;
            }
        }
        return null;
    }

    // ① 源侧 BFS：从 NPC 站立位下的地面格出发，第一个踩到"分量匹配的路格"即最优接入点（§6 反着搜）
    private static BfsResult bfsToRoadComponent(ServerLevel level, long start, RoadGraph graph,
                                                Set<Integer> components, int maxDepth) {
        // 起点本身可能已在目标分量上（NPC 站在路上）
        if (RoadStore.INSTANCE.roadCells(graph.dimension()).contains(start)
                && components.contains(componentOfPosition(graph, start))) {
            return new BfsResult(start, List.of(start));
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
                            && components.contains(componentOfPosition(graph, next))) {
                        List<Long> path = trace(cameFrom, start, next);
                        return new BfsResult(next, path);
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static int componentOfPosition(RoadGraph graph, long pos) {
        int node = graph.nodeAt(pos);
        return node < 0 ? -1 : graph.componentOf(node);
    }

    // ② 路网 Dijkstra：接入格 → 目的地接入格集合中最近的那个（CSR 数组遍历，微秒级）
    private static DijkstraResult dijkstraToAccess(RoadGraph graph, int start, List<AccessCell> destAccess) {
        int n = graph.nodeCount();
        float[] dist = new float[n];
        int[] cameFrom = new int[n];
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        java.util.Arrays.fill(cameFrom, -1);
        dist[start] = 0.0F;
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>(
                (a, b) -> Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));
        open.add(new long[] {start, Float.floatToIntBits(0.0F)});
        Set<Integer> goalNodes = new HashSet<>();
        for (AccessCell cell : destAccess) {
            int node = graph.nodeAt(cell.roadPos());
            if (node >= 0) {
                goalNodes.add(node);
            }
        }
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int node = (int) top[0];
            float d = Float.intBitsToFloat((int) top[1]);
            if (d > dist[node]) {
                continue;
            }
            if (goalNodes.contains(node) && node != start) {
                List<Long> roadPath = new ArrayList<>();
                int cursor = node;
                while (cursor >= 0) {
                    roadPath.add(graph.positionOf(cursor));
                    cursor = cameFrom[cursor];
                }
                java.util.Collections.reverse(roadPath);
                return new DijkstraResult(graph.positionOf(node), roadPath);
            }
            for (int edge = 0; edge < graph.neighborCount(node); edge++) {
                int next = graph.neighborTarget(node, edge);
                float nd = d + graph.neighborCost(node, edge);
                if (nd < dist[next]) {
                    dist[next] = nd;
                    cameFrom[next] = node;
                    open.add(new long[] {next, Float.floatToIntBits(nd)});
                }
            }
        }
        return null;
    }

    // ③ 出口格 → 建筑内目标点（stand 的地面格），限深 BFS
    private static BfsResult bfsToGoal(ServerLevel level, long start, long goal, int maxDepth) {
        if (start == goal) {
            return new BfsResult(goal, List.of(start));
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
                        return new BfsResult(goal, trace(cameFrom, start, goal));
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

    // 三段拼接：地面格序列 → 站立位 Waypoint（y+1），相邻去重；Move 由相邻 y 差决定
    private static List<NpcPathfinder.Waypoint> stitch(RoadGraph graph, BfsResult entry,
                                                       DijkstraResult crossing, BfsResult exit,
                                                       BlockPos from, BlockPos stand) {
        List<Long> cells = new ArrayList<>(entry.path);
        for (int i = 1; i < crossing.path.size(); i++) {
            cells.add(crossing.path.get(i));
        }
        for (int i = 1; i < exit.path.size(); i++) {
            cells.add(exit.path.get(i));
        }
        List<NpcPathfinder.Waypoint> waypoints = new ArrayList<>();
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (long cell : cells) {
            BlockPos pos = BlockPos.of(cell);
            if (pos.getX() == lastX && pos.getY() == lastY && pos.getZ() == lastZ) {
                continue;
            }
            waypoints.add(toWaypoint(waypoints, pos.getX(), pos.getY() + 1, pos.getZ()));
            lastX = pos.getX();
            lastY = pos.getY();
            lastZ = pos.getZ();
        }
        BlockPos standGround = BlockPos.of(cells.get(cells.size() - 1));
        if (standGround.getX() != stand.getX() || standGround.getZ() != stand.getZ()
                || Math.abs(standGround.getY() + 1 - stand.getY()) > 1) {
            waypoints.add(toWaypoint(waypoints, stand.getX(), stand.getY(), stand.getZ()));
        }
        return waypoints.size() > 1 ? waypoints : null;
    }

    private static NpcPathfinder.Waypoint toWaypoint(List<NpcPathfinder.Waypoint> waypoints,
                                                     int x, int y, int z) {
        int dy = waypoints.isEmpty() ? 0 : y - waypoints.get(waypoints.size() - 1).pos().getY();
        NpcPathfinder.Waypoint.Move move = dy > 0 ? NpcPathfinder.Waypoint.Move.STEP_UP
                : dy < 0 ? NpcPathfinder.Waypoint.Move.DROP
                : NpcPathfinder.Waypoint.Move.WALK;
        return new NpcPathfinder.Waypoint(new BlockPos(x, y, z), move);
    }

    private static List<Long> trace(Map<Long, Long> cameFrom, long start, long goal) {
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

    private static boolean isStandable(ServerLevel level, long pos) {
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

    private record BfsResult(long goal, List<Long> path) {
    }

    private record DijkstraResult(long goal, List<Long> path) {
    }
}
