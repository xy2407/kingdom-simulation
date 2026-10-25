package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 方块实体注册表：建筑工方块的材料容器
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModMain.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BuilderBlockEntity>> BUILDER =
            BLOCK_ENTITIES.register("builder_block",
                    () -> BlockEntityType.Builder.of(BuilderBlockEntity::new,
                            ModBlocks.BUILDER_BLOCK.get()).build(null));

    private ModBlockEntities() {
    }
}
