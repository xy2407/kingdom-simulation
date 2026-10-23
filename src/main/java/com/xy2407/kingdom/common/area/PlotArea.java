package com.xy2407.kingdom.common.area;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 作业范围：玩家在 RTS 下用右键刷出的逐格集合（保留原始形状，不转矩形）。
 * 同一建筑同一类型只保留一份，重新框选即整体替换。
 */
public record PlotArea(UUID id, UUID buildingId, PlotAreaKind kind, String dimension,
                       List<Long> cells, long createdDay) {

    public static final int MAX_CELLS = 65536;

    public PlotArea withCells(List<Long> newCells) {
        return new PlotArea(id, buildingId, kind, dimension, newCells, createdDay);
    }

    public boolean hasArea() {
        return cells != null && !cells.isEmpty();
    }

    public int size() {
        return cells == null ? 0 : cells.size();
    }

    public static List<Long> normalize(long[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        int limit = Math.min(raw.length, MAX_CELLS);
        for (int index = 0; index < limit; index++) {
            unique.add(raw[index]);
        }
        List<BlockPos> sorted = new ArrayList<>(unique.size());
        for (long packed : unique) {
            sorted.add(BlockPos.of(packed));
        }
        sorted.sort((a, b) -> a.getZ() != b.getZ()
                ? Integer.compare(a.getZ(), b.getZ())
                : Integer.compare(a.getX(), b.getX()));
        List<Long> cells = new ArrayList<>(sorted.size());
        for (BlockPos pos : sorted) {
            cells.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
        }
        return cells;
    }

    public static List<Long> normalize(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        long[] array = new long[raw.size()];
        for (int index = 0; index < array.length; index++) {
            Long value = raw.get(index);
            array[index] = value == null ? 0L : value;
        }
        return normalize(array);
    }

    public long[] toArray() {
        if (cells == null || cells.isEmpty()) {
            return new long[0];
        }
        long[] array = new long[cells.size()];
        for (int index = 0; index < array.length; index++) {
            Long value = cells.get(index);
            array[index] = value == null ? 0L : value;
        }
        return array;
    }
}