package com.xy2407.kingdom.client.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.farm.FarmRegion;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端待定农田：一次框选的结果加上还没提交的作物与打理人
 */
@OnlyIn(Dist.CLIENT)
public record FarmPlotDraft(int slots, String cropId, List<UUID> workerIds,
                            int minX, int minZ, int maxX, int maxZ, int groundY, int cells,
                            List<FarmRegion> extra) {

    private static FarmPlotDraft current;

    public FarmPlotDraft {
        cropId = cropId == null ? "" : cropId;
        workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
        extra = extra == null ? List.of() : List.copyOf(extra);
        int lowX = Math.min(minX, maxX);
        int highX = Math.max(minX, maxX);
        int lowZ = Math.min(minZ, maxZ);
        int highZ = Math.max(minZ, maxZ);
        minX = lowX;
        maxX = highX;
        minZ = lowZ;
        maxZ = highZ;
        cells = Math.max(0, cells);
    }

    public static FarmPlotDraft current() {
        return current;
    }

    public static boolean exists() {
        return current != null;
    }

    public static void set(FarmPlotDraft draft) {
        current = draft;
    }

    public static void clear() {
        current = null;
    }

    public FarmPlotDraft withSlots(int value) {
        return new FarmPlotDraft(Math.max(0, value), cropId, workerIds, minX, minZ, maxX, maxZ, groundY, cells, extra);
    }

    public FarmPlotDraft withCrop(String id) {
        return new FarmPlotDraft(slots, id, workerIds, minX, minZ, maxX, maxZ, groundY, cells, extra);
    }

    public FarmPlotDraft toggleWorker(UUID workerId) {
        if (workerId == null) {
            return withWorkers(List.of());
        }
        List<UUID> next = new ArrayList<>(workerIds);
        if (!next.remove(workerId)) {
            next.add(workerId);
        }
        return withWorkers(next);
    }

    public FarmPlotDraft withWorkers(List<UUID> next) {
        return new FarmPlotDraft(slots, cropId, next, minX, minZ, maxX, maxZ, groundY, cells, extra);
    }

    public String workerCsv() {
        StringBuilder builder = new StringBuilder();
        for (UUID id : workerIds) {
            if (id == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }
}
