package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 路径后处理拉直：把 A* 的 4 向阶梯路径合并成可行的直线段，供执行层沿直线驱动。
 */
public final class PathSmoother {

    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 4;

    private PathSmoother() {
    }

    public static List<NpcPathfinder.Waypoint> smooth(List<NpcPathfinder.Waypoint> path,
                                                     SnapshotView view) {
        if (path == null || path.size() <= 2) {
            return path;
        }
        List<NpcPathfinder.Waypoint> out = new ArrayList<>(path.size());
        int anchor = 0;
        out.add(path.get(0));
        while (anchor < path.size() - 1) {
            int next = anchor + 1;
            for (int j = path.size() - 1; j > anchor; j--) {
                if (canWalkLine(path.get(anchor).pos(), path.get(j).pos(), view)) {
                    next = j;
                    break;
                }
            }
            out.add(path.get(next));
            anchor = next;
        }
        return out;
    }

    private static boolean canWalkLine(BlockPos from, BlockPos to, SnapshotView view) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps == 0) {
            return true;
        }
        int prevX = from.getX();
        int prevZ = from.getZ();
        int prevY = from.getY();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            if (x == prevX && z == prevZ) {
                continue;
            }
            if (!standableAt(view, x, y, z)) {
                return false;
            }
            int dy = y - prevY;
            if (dy > MAX_STEP_UP || dy < -MAX_DROP) {
                return false;
            }
            if (x != prevX && z != prevZ) {
                if (!standableAt(view, prevX, y, z) || !standableAt(view, x, y, prevZ)) {
                    return false;
                }
            }
            prevX = x;
            prevY = y;
            prevZ = z;
        }
        return true;
    }

    private static boolean standableAt(SnapshotView view, int x, int y, int z) {
        return view.standable(x, y, z) && view.standHazard(x, y, z) == ChunkSnapshot.HAZARD_NONE;
    }
}
