package com.xy2407.kingdom.common.building;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 建筑登记条目，记录蓝图名、包围盒、分类与玩家为它选定的工种
 */
public record BuildingRecord(UUID id, String name, String dimension, BlockPos min, BlockPos max,
                             int level, int rotation, int farmSlots, int createdDay,
                             BuildingType type, BuildingWork work) {

    public BuildingRecord {
        level = Math.max(1, level);
    }

    public boolean contains(String dimensionId, double x, double y, double z) {
        if (!dimension.equals(dimensionId)) {
            return false;
        }
        return x >= min.getX() && x <= max.getX() + 1.0D
                && y >= min.getY() && y <= max.getY() + 1.0D
                && z >= min.getZ() && z <= max.getZ() + 1.0D;
    }

    public BuildingRecord withWork(BuildingWork chosen) {
        return new BuildingRecord(id, name, dimension, min, max, level, rotation, farmSlots,
                createdDay, type, chosen);
    }

    public BuildingRecord withBounds(BlockPos newMin, BlockPos newMax) {
        return new BuildingRecord(id, name, dimension, newMin, newMax, level, rotation, farmSlots,
                createdDay, type, work);
    }

    public boolean typed() {
        return work != null;
    }
}
