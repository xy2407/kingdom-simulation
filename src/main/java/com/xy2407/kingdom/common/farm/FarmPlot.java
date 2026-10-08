package com.xy2407.kingdom.common.farm;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 一块农田：核心矩形加附加散落块，带指定作物与打理人
 */
public record FarmPlot(UUID id, UUID buildingId, String blueprintName, String name,
                       int slots, String cropId, List<UUID> workerIds,
                       String dimension, BlockPos min, BlockPos max, int createdDay,
                       List<FarmRegion> extra) {

    public FarmPlot {
        name = name == null ? "" : name;
        blueprintName = blueprintName == null ? "" : blueprintName;
        cropId = cropId == null ? "" : cropId;
        workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
        dimension = dimension == null ? "" : dimension;
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public boolean hasCore() {
        return max.getX() > min.getX() || max.getZ() > min.getZ();
    }

    public boolean hasArea() {
        return hasCore() || !extra.isEmpty();
    }

    public int areaBlocks() {
        int total = hasCore() ? (max.getX() - min.getX() + 1) * (max.getZ() - min.getZ() + 1) : 0;
        for (FarmRegion region : extra) {
            total += region.cells();
        }
        return total;
    }
}
