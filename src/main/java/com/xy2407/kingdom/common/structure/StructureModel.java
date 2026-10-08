package com.xy2407.kingdom.common.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 一份已解析的建筑结构：相对坐标的方块表与包围盒尺寸，与加载器、渲染层解耦。
 */
public final class StructureModel {

    public record BlockEntry(BlockPos pos, BlockState state) {
    }

    private final Vec3i size;
    private final List<BlockEntry> blocks;
    private final int totalCuboids;

    public StructureModel(Vec3i size, List<BlockEntry> blocks) {
        this.size = size;
        this.blocks = List.copyOf(blocks);
        this.totalCuboids = size.getX() * size.getY() * size.getZ();
    }

    public Vec3i size() {
        return size;
    }

    public List<BlockEntry> blocks() {
        return blocks;
    }

    public int totalCuboids() {
        return totalCuboids;
    }

    public int maxDimension() {
        return Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
    }
}
