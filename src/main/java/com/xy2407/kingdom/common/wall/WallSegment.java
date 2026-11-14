package com.xy2407.kingdom.common.wall;

import net.minecraft.core.BlockPos;

/**
 * 城墙墙段数据：两端节点之间的连线，成品由生成器逐柱产出
 */
public record WallSegment(String key, String dimension, BlockPos a, BlockPos b,
                          String status, int progress, int total) {

    public WallSegment advance(int newProgress, String newStatus) {
        return new WallSegment(key, dimension, a, b, newStatus, newProgress, total);
    }

    public WallSegment withTotal(int newTotal) {
        return new WallSegment(key, dimension, a, b, status, progress, newTotal);
    }
}
