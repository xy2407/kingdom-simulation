package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.path.NpcPathfinder;

import net.minecraft.core.BlockPos;

/**
 * 三段拼接：地面格序列合成站立位 Waypoint 列表，相邻重复格去重。
 */
final class RoadPathStitcher {

    private RoadPathStitcher() {
    }

    static List<NpcPathfinder.Waypoint> stitch(RoadBfs.Bfs entry, RoadDijkstra.Crossing crossing,
                                               RoadBfs.Bfs exit, BlockPos stand) {
        List<Long> cells = new ArrayList<>(entry.path());
        for (int i = 1; i < crossing.path().size(); i++) {
            cells.add(crossing.path().get(i));
        }
        for (int i = 1; i < exit.path().size(); i++) {
            cells.add(exit.path().get(i));
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
}
