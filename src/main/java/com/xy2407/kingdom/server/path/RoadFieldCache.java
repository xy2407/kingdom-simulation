package com.xy2407.kingdom.server.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.RoadGraph;

import net.minecraft.server.level.ServerLevel;

/**
 * 目的地前驱场缓存（P4/P5，档 B）：对每个目的地建筑，从其接入格做多源 Dijkstra，
 * next[r] = 从路格 r 往该建筑走的下一跳（-1 = 已到达接入格）；之后任意 NPC 接入 r 只查表，
 * 第②段从"每趟 Dijkstra"降为 O(路径长度)，一次算、所有 NPC 复用。
 * 缓存绑定构建时的 RoadGraph 引用——路图重建（node id 重排）即自动失效重算；
 * LRU 上限 32 个场（D9），内存态不持久化。计算在服务端线程（寻路提交入口），同步毫秒级。
 */
public final class RoadFieldCache {

    private static final int MAX_FIELDS = 32;

    private static final Map<String, FieldEntry> FIELDS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FieldEntry> eldest) {
            return size() > MAX_FIELDS;
        }
    };

    private record FieldEntry(RoadGraph graph, int[] next) {
    }

    private RoadFieldCache() {
    }

    public static void reset() {
        FIELDS.clear();
    }

    public static int[] nextField(ServerLevel level, BuildingRecord destination, RoadGraph graph) {
        String key = destination.id().toString();
        FieldEntry entry = FIELDS.get(key);
        if (entry != null && entry.graph() == graph) {
            return entry.next();
        }
        List<AccessCell> destAccess = BuildingRoadAccess.accessOf(level, destination);
        if (destAccess.isEmpty()) {
            return null;
        }
        List<Integer> goalNodes = new ArrayList<>();
        for (AccessCell cell : destAccess) {
            int node = graph.nodeAt(cell.roadPos());
            if (node >= 0) {
                goalNodes.add(node);
            }
        }
        if (goalNodes.isEmpty()) {
            return null;
        }
        int n = graph.nodeCount();
        float[] dist = new float[n];
        int[] next = new int[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(next, -1);
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>(
                (a, b) -> Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));
        for (int goal : goalNodes) {
            dist[goal] = 0.0F;
            open.add(new long[] {goal, Float.floatToIntBits(0.0F)});
        }
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int node = (int) top[0];
            float d = Float.intBitsToFloat((int) top[1]);
            if (d > dist[node]) {
                continue;
            }
            for (int edge = 0; edge < graph.neighborCount(node); edge++) {
                int target = graph.neighborTarget(node, edge);
                float nd = d + graph.neighborCost(node, edge);
                if (nd < dist[target]) {
                    dist[target] = nd;
                    next[target] = node;
                    open.add(new long[] {target, Float.floatToIntBits(nd)});
                }
            }
        }
        FIELDS.put(key, new FieldEntry(graph, next));
        return next;
    }
}
