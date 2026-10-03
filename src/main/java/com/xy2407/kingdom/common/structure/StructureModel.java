package com.xy2407.kingdom.common.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 一份已解析的建筑结构，与加载器和渲染层解耦的纯数据。
 *
 * <p>方块位置是结构内的相对坐标，原点在结构包围盒的最小角。空气方块在解析阶段
 * 就被丢弃，因此 {@link #blocks()} 里只有需要参与渲染的方块。</p>
 */
public final class StructureModel {

    /** 单个方块：结构内相对坐标 + 方块状态（含属性，楼梯朝向、台阶上下半等都在里面）。 */
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

    /** 包围盒体积（含空气），用于按体积决定 LOD 档位。 */
    public int totalCuboids() {
        return totalCuboids;
    }

    /** 最长边，用于相机初始距离。 */
    public int maxDimension() {
        return Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
    }
}