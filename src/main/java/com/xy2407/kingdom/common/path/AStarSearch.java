package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * 快照 A* 搜索本体：四向扩展、向下跌落、同列攀爬，出路径后做拉直。
 */
final class AStarSearch {

    private static final int DOWNWARD_RANGE = 4;
    private static final double HEURISTIC_WEIGHT = 1.05D;

    private final SnapshotView view;
    private final int startX;
    private final int startY;
    private final int startZ;
    private final int endX;
    private final int endY;
    private final int endZ;
    private final boolean preferRoad;
    private final float minStep;
    private int minY;
    private int maxY;
    final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
    int visited;
    private long goalKey;

    AStarSearch(SnapshotView view, int startX, int startY, int startZ, int endX, int endY, int endZ) {
        this(view, startX, startY, startZ, endX, endY, endZ, true);
    }

    AStarSearch(SnapshotView view, int startX, int startY, int startZ, int endX, int endY, int endZ,
                boolean preferRoad) {
        this.view = view;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.preferRoad = preferRoad;
        this.minStep = preferRoad ? PathCostModel.ROAD_FACTOR : 1.0F;
        clampY();
    }

    private void clampY() {
        this.minY = Math.min(startY, endY) - NpcPathfinder.Y_SLACK;
        this.maxY = Math.max(startY, endY) + NpcPathfinder.Y_SLACK;
    }

    List<NpcPathfinder.Waypoint> run() {
        Node start = create(startX, startY, startZ, 0.0F);
        Node goal = create(endX, endY, endZ, Float.MAX_VALUE);
        nodes.put(BlockPos.asLong(startX, startY, startZ), start);
        goalKey = BlockPos.asLong(endX, endY, endZ);
        nodes.put(goalKey, goal);
        start.h = (float) (heuristic(start, endX, endY, endZ) * HEURISTIC_WEIGHT);
        start.f = start.g + start.h;
        open.add(start);
        return drain();
    }

    private List<NpcPathfinder.Waypoint> drain() {
        Node goal = nodes.get(goalKey);
        Node found = null;
        while (!open.isEmpty() && visited < NpcPathfinder.MAX_NODES) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            visited++;
            if (current == goal) {
                found = current;
                break;
            }
            expand(current);
        }
        if (found == null || found.g > PathCostModel.MAX_PATH_COST) {
            return List.of();
        }
        List<NpcPathfinder.Waypoint> waypoints = new ArrayList<>();
        for (Node node = found; node != null; node = node.cameFrom) {
            Node from = node.cameFrom;
            waypoints.add(new NpcPathfinder.Waypoint(new BlockPos(node.x, node.y, node.z),
                    from == null ? NpcPathfinder.Waypoint.Move.WALK : moveOf(from, node)));
        }
        Collections.reverse(waypoints);
        return PathSmoother.smooth(waypoints, view);
    }

    private NpcPathfinder.Waypoint.Move moveOf(Node from, Node to) {
        double dh = heightAt(view, to.x, to.y, to.z) - heightAt(view, from.x, from.y, from.z);
        if (dh > PathCostModel.STEP_THRESHOLD) {
            boolean sameColumn = from.x == to.x && from.z == to.z;
            return sameColumn ? NpcPathfinder.Waypoint.Move.CLIMB : NpcPathfinder.Waypoint.Move.STEP_UP;
        }
        if (dh < -PathCostModel.STEP_THRESHOLD) {
            boolean onLadder = view.climbable(from.x, from.y, from.z)
                    || view.climbable(to.x, to.y, to.z);
            return onLadder ? NpcPathfinder.Waypoint.Move.CLIMB : NpcPathfinder.Waypoint.Move.DROP;
        }
        return NpcPathfinder.Waypoint.Move.WALK;
    }

    private void expand(Node current) {
        double currentHeight = heightAt(view, current.x, current.y, current.z);
        if (view.climbable(current.x, current.y, current.z)) {
            offer(current, current.x, current.y + 1, current.z, currentHeight);
            offer(current, current.x, current.y - 1, current.z, currentHeight);
        }
        for (Direction direction : NpcPathfinder.HORIZONTALS) {
            int x = current.x + direction.getStepX();
            int z = current.z + direction.getStepZ();
            offer(current, x, current.y, z, currentHeight);
            offer(current, x, current.y + 1, z, currentHeight);
            for (int dy = -1; dy >= -DOWNWARD_RANGE; dy--) {
                int y = current.y + dy;
                if (view.blocked(x, y, z)) {
                    break;
                }
                if (view.standable(x, y, z)) {
                    offer(current, x, y, z, currentHeight);
                    break;
                }
            }
        }
    }

    private void offer(Node current, int x, int y, int z, double currentHeight) {
        if (y < minY || y > maxY) {
            return;
        }
        long key = BlockPos.asLong(x, y, z);
        Node node = nodes.get(key);
        if (node == null) {
            if (!view.standable(x, y, z)) {
                return;
            }
            node = create(x, y, z, Float.MAX_VALUE);
            nodes.put(key, node);
        } else if (node.closed || current.g + minStep > node.g) {
            return;
        }
        double dh = heightAt(view, x, y, z) - currentHeight;
        float raw = (float) PathCostModel.stepCost(dh);
        float step = raw;
        if (preferRoad && view.onRoad(x, y, z)) {
            step = PathCostModel.ROAD_FACTOR + (raw - 1.0F);
        }
        float g = current.g + step
                + PathCostModel.hazardCost(view.standHazard(x, y, z), view.cactusAdjacent(x, y, z));
        if (g >= node.g) {
            return;
        }
        node.g = g;
        node.cameFrom = current;
        node.h = (float) (heuristic(node, endX, endY, endZ) * HEURISTIC_WEIGHT);
        node.f = node.g + node.h;
        open.add(node);
    }

    private static Node create(int x, int y, int z, float g) {
        Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        node.g = g;
        return node;
    }

    private static double heightAt(SnapshotView view, int x, int y, int z) {
        return y - (view.halfFloor(x, y, z) ? 0.5D : 0.0D);
    }

    private static double heuristic(Node from, int x, int y, int z) {
        double dx = (double) from.x - x;
        double dy = (double) from.y - y;
        double dz = (double) from.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
