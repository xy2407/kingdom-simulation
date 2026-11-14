package com.xy2407.kingdom.common.wall;

import net.minecraft.core.BlockPos;

/**
 * 城墙节点数据：城楼或城门，落盘后按 key 唯一定位
 */
public record WallNode(String key, WallKind kind, String dimension, BlockPos pos, int rotation,
                       String status, int progress, int total, String territoryId, String owner) {

    public static final String PLANNED = "PLANNED";
    public static final String BUILDING = "BUILDING";
    public static final String DONE = "DONE";

    public WallNode advance(int newProgress, String newStatus) {
        return new WallNode(key, kind, dimension, pos, rotation, newStatus,
                newProgress, total, territoryId, owner);
    }

    public WallNode withTotal(int newTotal) {
        return new WallNode(key, kind, dimension, pos, rotation, status,
                progress, newTotal, territoryId, owner);
    }
}
