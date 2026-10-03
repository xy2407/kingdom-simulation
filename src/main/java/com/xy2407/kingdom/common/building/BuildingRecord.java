package com.xy2407.kingdom.common.building;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 建筑登记条目：蓝图、包围盒范围，以及完工时抄下的种植格上限快照
 */
public record BuildingRecord(UUID id, String name, String dimension, BlockPos min, BlockPos max,
                             int level, int rotation, int farmSlots, int createdDay) {

    public boolean contains(String dimensionId, double x, double y, double z) {
        if (!dimension.equals(dimensionId)) {
            return false;
        }
        return x >= min.getX() && x <= max.getX() + 1.0D
                && y >= min.getY() && y <= max.getY() + 1.0D
                && z >= min.getZ() && z <= max.getZ() + 1.0D;
    }
}
