package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 方块实体注册表：建筑工方块的材料容器与商铺货架的货物容器
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModMain.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BuilderBlockEntity>> BUILDER =
            BLOCK_ENTITIES.register("builder_block",
                    () -> BlockEntityType.Builder.of(BuilderBlockEntity::new,
                            ModBlocks.BUILDER_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShopShelfBlockEntity>> SHOP_SHELF =
            BLOCK_ENTITIES.register("shop_shelf",
                    () -> BlockEntityType.Builder.of(ShopShelfBlockEntity::new,
                            ModBlocks.SHOP_SHELF.get()).build(null));

    private ModBlockEntities() {
    }
}
