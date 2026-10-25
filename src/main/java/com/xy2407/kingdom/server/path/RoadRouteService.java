package com.xy2407.kingdom.server.path;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.RoadGraph;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 道路路由：三段式（源侧 BFS + 路网 Dijkstra + 出口 BFS）拼成 Waypoint，失败返回 null 回退异步寻路。
 */
public final class RoadRouteService {

    private static final int ENTRY_RADIUS = 32;

    private static final int[] ACCESS_RADII = {8, 16, 32, 64};

    private static final int BUILDING_DEPTH = 20;

    private static final double NEAR_DIRECT_SQR = 1024.0D;

    private static final Map<Long, Long> NO_ACCESS_UNTIL = new ConcurrentHashMap<>();
    private static final long NO_ACCESS_TTL_TICKS = 600L;
    private static final int NO_ACCESS_CAPACITY = 4096;

    private RoadRouteService() {
    }

    public static List<NpcPathfinder.Waypoint> tryPlan(ServerLevel level, BlockPos from, BlockPos stand) {
        if (from.distSqr(stand) <= NEAR_DIRECT_SQR) {
            return null;
        }
        MinecraftServer server = level.getServer();
        String dimension = level.dimension().location().toString();
        BuildingRecord destination = buildingAt(dimension, stand);
        RoadGraph graph = RoadGraphService.current(server, dimension);
        if (graph == null || graph.nodeCount() == 0) {
            return null;
        }
        List<AccessCell> destAccess = accessOf(level, destination, graph, stand);
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
        RoadBfs.Bfs entry = RoadBfs.toRoadComponent(level, fromGround, graph, destComponents, ENTRY_RADIUS);
        boolean bridged = false;
        if (entry == null) {
            RoadExtender.Bridge bridge = RoadExtender.bridge(level, graph, fromGround, destAccess, ENTRY_RADIUS);
            if (bridge == null) {
                return null;
            }
            entry = bridge.entry();
            destAccess = bridge.destAccess();
            bridged = true;
        }
        int entryNode = graph.nodeAt(entry.goal());
        if (entryNode < 0) {
            return null;
        }
        List<Long> roadPath = destination == null || bridged
                ? null : RoadDijkstra.walkField(graph, entryNode,
                        RoadFieldCache.nextField(level, destination, graph));
        RoadDijkstra.Crossing crossing;
        if (roadPath != null) {
            crossing = new RoadDijkstra.Crossing(roadPath.get(roadPath.size() - 1), roadPath);
        } else {
            crossing = RoadDijkstra.toAccess(graph, entryNode, destAccess);
        }
        if (crossing == null) {
            return null;
        }
        RoadBfs.Bfs exit = RoadBfs.toGoal(level, crossing.goal(), standGround, BUILDING_DEPTH);
        if (exit == null) {
            return null;
        }
        return RoadPathStitcher.stitch(entry, crossing, exit, stand);
    }

    private static List<AccessCell> accessOf(ServerLevel level, BuildingRecord destination,
                                             RoadGraph graph, BlockPos stand) {
        if (destination != null) {
            List<AccessCell> access = BuildingRoadAccess.accessOf(level, destination);
            if (!access.isEmpty()) {
                return access;
            }
        }
        Long blockedUntil = NO_ACCESS_UNTIL.get(stand.asLong());
        if (blockedUntil != null && level.getGameTime() < blockedUntil) {
            return List.of();
        }
        for (int radius : ACCESS_RADII) {
            int node = graph.nearest(stand.getX(), stand.getY(), stand.getZ(), radius);
            if (node >= 0) {
                BlockPos road = BlockPos.of(graph.positionOf(node));
                return List.of(new AccessCell(road.asLong(), (float) stand.distSqr(road)));
            }
        }
        NO_ACCESS_UNTIL.put(stand.asLong(), level.getGameTime() + NO_ACCESS_TTL_TICKS);
        return List.of();
    }

    public static void clearNoAccess() {
        NO_ACCESS_UNTIL.clear();
    }

    private static BuildingRecord buildingAt(String dimension, BlockPos stand) {
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (record.contains(dimension, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D)) {
                return record;
            }
        }
        return null;
    }
}
