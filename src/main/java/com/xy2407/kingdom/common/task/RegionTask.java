package com.xy2407.kingdom.common.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 区域施工任务：拆除自顶向下、填充自底向上逐层推进，NPC 站周边挥手模拟
 */
public final class RegionTask {

    public enum Kind { FILL, DEMOLISH }

    public enum Status { READY, RUNNING, PAUSED }

    public record Material(String itemId, int count) {
    }

    private final UUID id;
    private final Kind kind;
    private final String dimension;
    private final BlockPos min;
    private final BlockPos max;
    private final String fillItem;
    private final List<Material> materials;
    private final int total;
    private final int createdDay;
    private final List<UUID> assigned = new ArrayList<>();
    private Status status = Status.READY;
    private int cursor;

    public RegionTask(UUID id, Kind kind, String dimension, BlockPos min, BlockPos max,
                      String fillItem, List<Material> materials, int total, int createdDay) {
        this.id = id;
        this.kind = kind;
        this.dimension = dimension;
        this.min = min.immutable();
        this.max = max.immutable();
        this.fillItem = fillItem == null || fillItem.isBlank() ? "minecraft:dirt" : fillItem;
        this.materials = materials == null ? List.of() : List.copyOf(materials);
        this.total = Math.max(0, total);
        this.createdDay = createdDay;
    }

    public UUID id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    public String fillItem() {
        return fillItem;
    }

    public List<Material> materials() {
        return materials;
    }

    public int total() {
        return total;
    }

    public int createdDay() {
        return createdDay;
    }

    public List<UUID> assigned() {
        return assigned;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status value) {
        status = value == null ? Status.READY : value;
    }

    public int cursor() {
        return cursor;
    }

    public void advance() {
        cursor++;
    }

    public boolean isFinished() {
        return cursor >= total;
    }

    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    public int layerCount() {
        return max.getY() - min.getY() + 1;
    }

    public boolean contains(UUID npcId) {
        return npcId != null && assigned.contains(npcId);
    }

    public int layerBlocks() {
        return sizeX() * sizeZ();
    }

    public int layerIndex() {
        int layer = cursor / Math.max(1, layerBlocks());
        return Math.min(layer, layerCount() - 1);
    }

    public int layerY() {
        return kind == Kind.DEMOLISH ? max.getY() - layerIndex() : min.getY() + layerIndex();
    }

    public BlockPos posOf(int index) {
        int layer = Math.min(index / Math.max(1, layerBlocks()), layerCount() - 1);
        int within = index % Math.max(1, layerBlocks());
        int y = kind == Kind.DEMOLISH ? max.getY() - layer : min.getY() + layer;
        return new BlockPos(min.getX() + within % sizeX(), y, min.getZ() + within / sizeX());
    }
}
