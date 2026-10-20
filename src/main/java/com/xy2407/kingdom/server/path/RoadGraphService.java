package com.xy2407.kingdom.server.path;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.path.RoadGraph;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.server.MinecraftServer;

/**
 * 路图快照的持有与懒重建（D3/D7 已定）：路格变更只标脏，下一次查询时才重建（服务端线程），
 * 无变更零开销；RoadGraph 不可变整体替换引用，A* 线程只读。
 */
public final class RoadGraphService {

    private static final Map<String, Boolean> DIRTY = new ConcurrentHashMap<>();

    private static final Map<String, RoadGraph> GRAPHS = new ConcurrentHashMap<>();

    private RoadGraphService() {
    }

    public static void reset() {
        DIRTY.clear();
        GRAPHS.clear();
    }

    public static void markDirty(String dimension) {
        DIRTY.put(dimension, Boolean.TRUE);
    }

    public static RoadGraph current(MinecraftServer server, String dimension) {
        RoadGraph graph = GRAPHS.get(dimension);
        if (graph != null && !Boolean.TRUE.equals(DIRTY.get(dimension))) {
            return graph;
        }
        if (server == null) {
            return graph;
        }
        Set<Long> cells = RoadStore.INSTANCE.roadCells(dimension);
        if (cells.isEmpty()) {
            GRAPHS.remove(dimension);
            DIRTY.remove(dimension);
            return null;
        }
        graph = RoadGraph.build(dimension, cells);
        GRAPHS.put(dimension, graph);
        DIRTY.remove(dimension);
        ModMain.LOGGER.info("[路图] {}：{} 个节点、{} 个连通分量",
                dimension, graph.nodeCount(), graph.componentCount());
        return graph;
    }
}
