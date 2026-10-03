package com.xy2407.kingdom.common.farm;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 一块农田：从所属建筑的种植格额度里分出的地块，带指定作物与打理人
 */
public record FarmPlot(UUID id, UUID buildingId, String blueprintName, String name,
                       int slots, String cropId, List<UUID> workerIds,
                       String dimension, BlockPos min, BlockPos max, int createdDay) {

    public FarmPlot {
        name = name == null ? "" : name;
        blueprintName = blueprintName == null ? "" : blueprintName;
        cropId = cropId == null ? "" : cropId;
        workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
        dimension = dimension == null ? "" : dimension;
    }

    public boolean hasArea() {
        return max.getX() > min.getX() || max.getZ() > min.getZ();
    }

    public int areaBlocks() {
        if (!hasArea()) {
            return 0;
        }
        return (max.getX() - min.getX() + 1) * (max.getZ() - min.getZ() + 1);
    }
}
