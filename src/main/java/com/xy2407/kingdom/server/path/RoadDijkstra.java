package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.RoadGraph;

/**
 * 路网最短路：单趟 Dijkstra 与目的地前驱场走表。
 */
final class RoadDijkstra {

    record Crossing(long goal, List<Long> path) {
    }

    private static final int NODE_BUDGET = 12000;

    private RoadDijkstra() {
    }

    static Crossing toAccess(RoadGraph graph, int start, List<AccessCell> destAccess) {
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
        int visited = 0;
        while (!open.isEmpty()) {
            if (++visited > NODE_BUDGET) {
                return null;
            }
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
                return new Crossing(graph.positionOf(node), roadPath);
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

    static List<Long> walkField(RoadGraph graph, int entryNode, int[] next) {
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
}
