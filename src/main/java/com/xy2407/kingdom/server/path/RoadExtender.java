package com.xy2407.kingdom.server.path;

import java.util.List;

import com.xy2407.kingdom.common.path.AccessCell;
import com.xy2407.kingdom.common.path.RoadGraph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 分级延展：异分量或吸附失败时用同心盒找桥接路格并做限深短验。
 */
final class RoadExtender {

    record Bridge(RoadBfs.Bfs entry, List<AccessCell> destAccess) {
    }

    private static final int[] EXTEND_RADII = {16, 32, 64};
    private static final int MAX_EXTEND_Y = 64;
    private static final int BRIDGE_DEPTH = 64;

    private RoadExtender() {
    }

    static Bridge bridge(ServerLevel level, RoadGraph graph, long fromGround,
                         List<AccessCell> destAccess, int entryRadius) {
        RoadBfs.Bfs any = RoadBfs.toAnyRoad(level, fromGround, graph, entryRadius);
        if (any == null) {
            return null;
        }
        int node = graph.nodeAt(any.goal());
        if (node < 0) {
            return null;
        }
        int sourceComponent = graph.componentOf(node);
        BlockPos target = BlockPos.of(destAccess.get(0).roadPos());
        for (int radius : EXTEND_RADII) {
            final int[] found = {-1};
            final double[] best = {Double.MAX_VALUE};
            graph.collectInBox(target.getX() - radius, target.getY() - MAX_EXTEND_Y,
                    target.getZ() - radius, target.getX() + radius,
                    target.getY() + MAX_EXTEND_Y, target.getZ() + radius, candidate -> {
                        if (graph.componentOf(candidate) != sourceComponent) {
                            return;
                        }
                        BlockPos pos = BlockPos.of(graph.positionOf(candidate));
                        double dist = pos.distSqr(target);
                        if (dist < best[0]) {
                            best[0] = dist;
                            found[0] = candidate;
                        }
                    });
            if (found[0] < 0) {
                continue;
            }
            BlockPos bridgePos = BlockPos.of(graph.positionOf(found[0]));
            if (RoadBfs.toGoal(level, any.goal(), bridgePos.asLong(), BRIDGE_DEPTH) == null) {
                continue;
            }
            return new Bridge(any, List.of(new AccessCell(bridgePos.asLong(), 0.0F)));
        }
        return null;
    }
}
