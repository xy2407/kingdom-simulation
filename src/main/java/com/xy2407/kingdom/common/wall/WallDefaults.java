package com.xy2407.kingdom.common.wall;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 城墙节点占位模型：蓝图缺失时用代码生成 5×5×12 的城楼/城门，保证投影与施工不中断
 */
public final class WallDefaults {

    public static final int SIZE_X = 5;

    public static final int SIZE_Y = 12;

    public static final int SIZE_Z = 5;

    private WallDefaults() {
    }

    public static List<WallBlock> blocks(WallKind kind) {
        List<WallBlock> result = new ArrayList<>();
        int stateId = Block.getId(Blocks.STONE_BRICKS.defaultBlockState());
        for (int y = 0; y < SIZE_Y; y++) {
            for (int x = 0; x < SIZE_X; x++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    boolean edge = x == 0 || x == SIZE_X - 1 || z == 0 || z == SIZE_Z - 1;
                    boolean floor = y == 0;
                    boolean platform = y == 9;
                    boolean battlement = y == 10 && edge && (x + z) % 2 == 0;
                    boolean post = y == 11 && (x == 0 || x == SIZE_X - 1)
                            && (z == 0 || z == SIZE_Z - 1);
                    boolean shell = edge && y <= 9;
                    if (!(floor || platform || battlement || post || shell)) {
                        continue;
                    }
                    if (kind == WallKind.GATE && x >= 1 && x <= 3 && y >= 1 && y <= 4) {
                        continue;
                    }
                    result.add(new WallBlock(x, y, z, stateId));
                }
            }
        }
        return result;
    }
}
