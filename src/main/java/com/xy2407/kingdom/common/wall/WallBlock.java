package com.xy2407.kingdom.common.wall;

import net.minecraft.core.BlockPos;

/**
 * 已放置的单个方块（坐标 + 方块状态 id），用于全量存库与拆除还原
 */
public record WallBlock(int x, int y, int z, int stateId) {

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }
}
