package com.xy2407.kingdom.client.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.road.RoadGeometry;
import com.xy2407.kingdom.common.road.RoadPolyline;

import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 世界道路的客户端仓库：未提交的折线（按笔分组，C 撤回整笔）、服务端已加载区块集与重绘版本号。
 */
@OnlyIn(Dist.CLIENT)
public final class RoadClientStore {

    // 一次右键拖拽 = 一笔；中途断线（跨未探索/未加载/领地内）时一笔里可能出现多条折线，撤回按整笔走
    public static final class Stroke {

        private final List<RoadPolyline> polylines = new ArrayList<>();

        private final List<double[]> open = new ArrayList<>();

        public void appendOpen(double x, double z) {
            open.add(new double[] {x, z});
        }

        public void closeOpen() {
            if (!open.isEmpty()) {
                polylines.add(RoadPolyline.of(open));
                open.clear();
            }
        }

        public boolean isEmpty() {
            return polylines.isEmpty() && open.isEmpty();
        }

        public List<RoadPolyline> polylines() {
            return polylines;
        }

        public List<double[]> openPoints() {
            return open;
        }
    }

    private static final Deque<Stroke> PENDING = new ArrayDeque<>();

    private static final Set<Long> LOADED_CHUNKS = new HashSet<>();

    private static int revision;

    private RoadClientStore() {
    }

    public static void beginStroke() {
        PENDING.push(new Stroke());
        revision++;
    }

    public static void appendPoint(double x, double z) {
        Stroke stroke = PENDING.peek();
        if (stroke != null) {
            stroke.appendOpen(x, z);
            revision++;
        }
    }

    public static void breakPolyline() {
        Stroke stroke = PENDING.peek();
        if (stroke != null) {
            stroke.closeOpen();
            revision++;
        }
    }

    public static void endStroke() {
        Stroke stroke = PENDING.peek();
        if (stroke == null) {
            return;
        }
        stroke.closeOpen();
        if (stroke.isEmpty()) {
            PENDING.pop();
        }
        revision++;
    }

    public static void undo() {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.pop();
        revision++;
    }

    public static void clearPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.clear();
        revision++;
    }

    public static Deque<Stroke> strokes() {
        return PENDING;
    }

    public static int revision() {
        return revision;
    }

    public static List<RoadPolyline> flatten() {
        List<RoadPolyline> all = new ArrayList<>();
        for (Stroke stroke : PENDING) {
            all.addAll(stroke.polylines());
            if (!stroke.openPoints().isEmpty()) {
                all.add(RoadPolyline.of(stroke.openPoints()));
            }
        }
        return all;
    }

    public static void setLoadedChunks(long[] keys) {
        LOADED_CHUNKS.clear();
        if (keys != null) {
            for (long key : keys) {
                LOADED_CHUNKS.add(key);
            }
        }
        revision++;
    }

    public static boolean isLoadedChunk(int chunkX, int chunkZ) {
        return LOADED_CHUNKS.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void reset() {
        PENDING.clear();
        LOADED_CHUNKS.clear();
        revision++;
    }

    public static boolean[] computeRooted(List<RoadPolyline> lines, Set<Long> ownChunks) {
        return RoadGeometry.computeRooted(lines, ownChunks);
    }

    public static Set<Long> coveredChunks(List<RoadPolyline> lines) {
        return RoadGeometry.coveredChunks(lines);
    }
}
