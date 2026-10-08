package com.xy2407.kingdom.common.ranch;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 一栏养殖数据：一座养殖建筑按动物分栏，每栏一条记录
 */
public record RanchPen(UUID id, UUID buildingId, String blueprintName, String animalId,
                       String dimension, BlockPos min, BlockPos max,
                       List<UUID> keeperIds, boolean running, String statusKey, long updatedAt) {

    public RanchPen {
        blueprintName = blueprintName == null ? "" : blueprintName;
        animalId = animalId == null ? "" : animalId;
        dimension = dimension == null ? "" : dimension;
        keeperIds = keeperIds == null ? List.of() : List.copyOf(keeperIds);
        statusKey = statusKey == null ? "" : statusKey;
        updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public boolean hasKeeper() {
        return !keeperIds.isEmpty();
    }

    public RanchPen withKeeperIds(List<UUID> keepers) {
        return new RanchPen(id, buildingId, blueprintName, animalId, dimension, min, max,
                keepers, running, statusKey, System.currentTimeMillis());
    }

    public RanchPen withRunning(boolean value) {
        return new RanchPen(id, buildingId, blueprintName, animalId, dimension, min, max,
                keeperIds, value, statusKey, System.currentTimeMillis());
    }

    public RanchPen withStatus(String key) {
        return new RanchPen(id, buildingId, blueprintName, animalId, dimension, min, max,
                keeperIds, running, key, System.currentTimeMillis());
    }

    public BlockPos anchor() {
        return min;
    }
}
