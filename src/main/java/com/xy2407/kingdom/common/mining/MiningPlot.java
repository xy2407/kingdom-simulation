package com.xy2407.kingdom.common.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 采矿场：按笔刷格集记录范围，蛇形游标逐格下挖，挂在一座采矿建筑下。
 */
public record MiningPlot(UUID id, UUID buildingId, String name, String dimension,
                         BlockPos min, BlockPos max, int surfaceY, int currentY, int cursor,
                         String status, List<UUID> workerIds, long createdDay, List<Long> cells) {

    public static final String STATUS_READY = "READY";

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_PAUSED = "PAUSED";

    public static final String STATUS_DONE = "DONE";

    public MiningPlot {
        workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
        cells = cells == null ? List.of() : List.copyOf(cells);
        status = status == null || status.isBlank() ? STATUS_READY : status;
    }

    public MiningPlot withWorkerIds(List<UUID> workers) {
        return new MiningPlot(id, buildingId, name, dimension, min, max, surfaceY, currentY, cursor,
                status, workers, createdDay, cells);
    }

    public static List<Long> normalize(long[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (long cell : raw) {
            unique.add(cell);
        }
        List<BlockPos> positions = new ArrayList<>(unique.size());
        for (long cell : unique) {
            positions.add(BlockPos.of(cell));
        }
        positions.sort(Comparator.comparingInt((BlockPos p) -> p.getZ())
                .thenComparingInt(p -> p.getX()));
        List<BlockPos> ordered = new ArrayList<>(positions.size());
        int index = 0;
        while (index < positions.size()) {
            int rowZ = positions.get(index).getZ();
            int end = index;
            while (end < positions.size() && positions.get(end).getZ() == rowZ) {
                end++;
            }
            List<BlockPos> row = new ArrayList<>(positions.subList(index, end));
            if (((ordered.size() == 0) ? 0 : rowZ - positions.get(0).getZ()) % 2 != 0) {
                row.sort(Comparator.comparingInt((BlockPos p) -> p.getX()).reversed());
            }
            ordered.addAll(row);
            index = end;
        }
        List<Long> result = new ArrayList<>(ordered.size());
        for (BlockPos pos : ordered) {
            result.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
        }
        return result;
    }

    public int width() {
        return max.getX() - min.getX() + 1;
    }

    public int depth() {
        return max.getZ() - min.getZ() + 1;
    }

    public int layerSize() {
        return cells.isEmpty() ? width() * depth() : cells.size();
    }

    public boolean hasArea() {
        return !cells.isEmpty() || (width() > 0 && depth() > 0);
    }

    public BlockPos posOf(int index) {
        if (cells.isEmpty()) {
            int width = Math.max(1, width());
            int row = index / width;
            int col = index % width;
            int x = row % 2 == 0 ? min.getX() + col : max.getX() - col;
            return new BlockPos(x, currentY, min.getZ() + row);
        }
        int safe = Math.max(0, Math.min(index, cells.size() - 1));
        BlockPos base = BlockPos.of(cells.get(safe));
        return new BlockPos(base.getX(), currentY, base.getZ());
    }

    public MiningPlot withProgress(int newCurrentY, int newCursor) {
        return new MiningPlot(id, buildingId, name, dimension, min, max, surfaceY,
                newCurrentY, newCursor, status, workerIds, createdDay, cells);
    }

    public MiningPlot withStatus(String newStatus) {
        return new MiningPlot(id, buildingId, name, dimension, min, max, surfaceY,
                currentY, cursor, newStatus, workerIds, createdDay, cells);
    }

    public MiningPlot withArea(BlockPos newMin, BlockPos newMax, int newSurfaceY, List<Long> newCells) {
        return new MiningPlot(id, buildingId, name, dimension, newMin, newMax, newSurfaceY,
                newSurfaceY - 1, 0, STATUS_RUNNING, workerIds, createdDay, newCells);
    }

    public MiningPlot withWorkers(List<UUID> newWorkers) {
        return new MiningPlot(id, buildingId, name, dimension, min, max, surfaceY,
                currentY, cursor, status, newWorkers, createdDay, cells);
    }
}
