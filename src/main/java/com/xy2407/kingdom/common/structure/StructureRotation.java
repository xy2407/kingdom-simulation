package com.xy2407.kingdom.common.structure;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 结构绕 Y 轴旋转工具，客户端预览与服务端施工共用
 */
public final class StructureRotation {

    private StructureRotation() {
    }

    /** 把角度归一到 0~3 个 90° 的圈数。 */
    public static int turns(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return normalized / 90;
    }

    /** 绕 Y 轴旋转整数个 90°，返回新模型；0 圈时原样返回。 */
    public static StructureModel rotate(StructureModel model, int degrees) {
        int turns = turns(degrees);
        if (turns == 0) {
            return model;
        }
        Vec3i size = model.size();
        Vec3i rotatedSize = turns == 2
                ? size
                : new Vec3i(size.getZ(), size.getY(), size.getX());
        List<StructureModel.BlockEntry> blocks = new ArrayList<>(model.blocks().size());
        for (StructureModel.BlockEntry entry : model.blocks()) {
            BlockPos pos = entry.pos();
            int x;
            int z;
            switch (turns) {
                case 1 -> {
                    x = size.getZ() - 1 - pos.getZ();
                    z = pos.getX();
                }
                case 2 -> {
                    x = size.getX() - 1 - pos.getX();
                    z = size.getZ() - 1 - pos.getZ();
                }
                default -> {
                    x = pos.getZ();
                    z = size.getX() - 1 - pos.getX();
                }
            }
            blocks.add(new StructureModel.BlockEntry(new BlockPos(x, pos.getY(), z),
                    rotateState(entry.state(), turns)));
        }
        return new StructureModel(rotatedSize, blocks);
    }

    private static BlockState rotateState(BlockState state, int turns) {
        if (state == null || turns == 0) {
            return state;
        }
        BlockState rotated = state;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof DirectionProperty directionProperty) {
                rotated = rotateProperty(rotated, directionProperty, turns);
            }
        }
        return rotated;
    }

    private static BlockState rotateProperty(BlockState state, DirectionProperty property, int turns) {
        Direction direction = state.getValue(property);
        Direction rotated = direction;
        for (int i = 0; i < turns; i++) {
            rotated = rotated.getClockWise();
        }
        return state.setValue(property, rotated);
    }
}
