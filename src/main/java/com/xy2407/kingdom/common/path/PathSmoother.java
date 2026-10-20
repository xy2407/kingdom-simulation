package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 路径后处理拉直：仅合并同一水平层内可直行的航点段，任何 y 偏移的相邻航点一律保留。
 */
public final class PathSmoother {

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
                if (isFlat(path, anchor, j)
                        && canWalkFlat(path.get(anchor).pos(), path.get(j).pos(), view)) {
                    next = j;
                    break;
                }
            }
            out.add(path.get(next));
            anchor = next;
        }
        return out;
    }

    private static boolean isFlat(List<NpcPathfinder.Waypoint> path, int from, int to) {
        int y = path.get(from).pos().getY();
        for (int i = from + 1; i <= to; i++) {
            if (path.get(i).pos().getY() != y) {
                return false;
            }
        }
        return true;
    }

    private static boolean canWalkFlat(BlockPos from, BlockPos to, SnapshotView view) {
        int y = from.getY();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            return true;
        }
        int prevX = from.getX();
        int prevZ = from.getZ();
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + (int) Math.round((double) dx * i / steps);
            int z = from.getZ() + (int) Math.round((double) dz * i / steps);
            if (!standableAt(view, x, y, z)) {
                return false;
            }
            if (x != prevX && z != prevZ
                    && (!standableAt(view, prevX, y, z) || !standableAt(view, x, y, prevZ))) {
                return false;
            }
            prevX = x;
            prevZ = z;
        }
        return true;
    }

    private static boolean standableAt(SnapshotView view, int x, int y, int z) {
        return view.standable(x, y, z) && view.standHazard(x, y, z) == ChunkSnapshot.HAZARD_NONE;
    }
}
