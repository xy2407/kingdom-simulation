package com.xy2407.kingdom.common.building;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 脚手架规划，外扩一圈矩形框，立柱每 7 格一根，横杆每 4 层一圈。
 */
public final class ScaffoldPlanner {
    public static final int HORIZONTAL_INTERVAL = 7;
    public static final int LAYER_INTERVAL = 4;
    private static final int MARGIN = 1;

    private ScaffoldPlanner() {
    }

    public static int scaffoldTop(int buildingHeight) {
        int sections = (Math.max(buildingHeight, 1) + LAYER_INTERVAL - 1) / LAYER_INTERVAL;
        return sections * LAYER_INTERVAL;
    }

    public static List<BlockPos> layer(Vec3i size, int y) {
        List<BlockPos> walk = perimeterWalk(size);
        Set<BlockPos> columnSet = new LinkedHashSet<>(columns(size));
        List<BlockPos> out = new ArrayList<>();
        boolean ringLayer = y % LAYER_INTERVAL == 0;
        for (BlockPos pos : walk) {
            if (columnSet.contains(pos) || ringLayer) {
                out.add(new BlockPos(pos.getX(), y, pos.getZ()));
            }
        }
        return out;
    }

    public static List<BlockPos> all(Vec3i size, int buildingHeight) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int y = 0; y < scaffoldTop(buildingHeight); y++) {
            out.addAll(layer(size, y));
        }
        return List.copyOf(out);
    }

    public static List<BlockPos> walkPositions(Vec3i size) {
        return perimeterWalk(size);
    }

    private static List<BlockPos> perimeterWalk(Vec3i size) {
        int minX = -MARGIN;
        int minZ = -MARGIN;
        int maxX = size.getX();
        int maxZ = size.getZ();
        List<BlockPos> out = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            out.add(new BlockPos(x, 0, minZ));
        }
        for (int z = minZ + 1; z <= maxZ; z++) {
            out.add(new BlockPos(maxX, 0, z));
        }
        for (int x = maxX - 1; x >= minX; x--) {
            out.add(new BlockPos(x, 0, maxZ));
        }
        for (int z = maxZ - 1; z > minZ; z--) {
            out.add(new BlockPos(minX, 0, z));
        }
        return out;
    }

    public static List<BlockPos> pillarPositions(Vec3i size) {
        return columns(size);
    }

    private static List<BlockPos> columns(Vec3i size) {
        List<BlockPos> walk = perimeterWalk(size);
        Set<BlockPos> corners = new LinkedHashSet<>(List.of(
                new BlockPos(-MARGIN, 0, -MARGIN),
                new BlockPos(-MARGIN, 0, size.getZ()),
                new BlockPos(size.getX(), 0, -MARGIN),
                new BlockPos(size.getX(), 0, size.getZ())));
        List<BlockPos> out = new ArrayList<>();
        for (int index = 0; index < walk.size(); index++) {
            BlockPos pos = walk.get(index);
            if (index % HORIZONTAL_INTERVAL == 0 || corners.contains(pos)) {
                out.add(pos);
            }
        }
        return out;
    }
}
