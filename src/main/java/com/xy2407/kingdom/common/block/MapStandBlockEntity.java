package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 仓库地图架方块实体：只是打开界面的入口。
 * 管辖关系已改存数据库（warehouse_link 表），方块本身不保存任何归属数据。
 */
public class MapStandBlockEntity extends BlockEntity {

    public MapStandBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MAP_STAND.get(), pos, state);
    }
}
