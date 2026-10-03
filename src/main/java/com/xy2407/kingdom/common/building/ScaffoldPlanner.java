package com.xy2407.kingdom.common.building;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 脚手架规划，外扩一圈矩形框，立柱每7格一根，横杆每4层一圈
 */
public final class ScaffoldPlanner {
    public static final int HORIZONTAL_INTERVAL = 7;
    public static final int LAYER_INTERVAL = 4;
    private static final int MARGIN = 1;

    private ScaffoldPlanner() {
    }

    /** 脚手架顶部高度：建筑层高向上取整到横杆间隔的整数倍，允许高出建筑 */
    public static int scaffoldTop(int buildingHeight) {
        int sections = (Math.max(buildingHeight, 1) + LAYER_INTERVAL - 1) / LAYER_INTERVAL;
        return sections * LAYER_INTERVAL;
    }

    /** 指定高度层的脚手架相对坐标，沿框单线行进：立柱恒在，横杆按层间隔补一圈 */
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

    /** 全部脚手架相对坐标，供完工拆除 */
    public static List<BlockPos> all(Vec3i size, int buildingHeight) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int y = 0; y < scaffoldTop(buildingHeight); y++) {
            out.addAll(layer(size, y));
        }
        return List.copyOf(out);
    }

    /** 外框行走路径（仅水平坐标），供施工走位选取站位 */
    public static List<BlockPos> walkPositions(Vec3i size) {
        return perimeterWalk(size);
    }

    /** 从 (minX, minZ) 角出发，沿矩形框顺时针单线走一圈 */
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

    /** 柱位（圈上每 7 格一根 + 四角），供施工序列排布 */
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
