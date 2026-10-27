package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 方块实体注册表：建筑工材料容器、商铺货架货物容器与木桌桌面展示
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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MapStandBlockEntity>> MAP_STAND =
            BLOCK_ENTITIES.register("map_stand",
                    () -> BlockEntityType.Builder.of(MapStandBlockEntity::new,
                            ModBlocks.MAP_STAND.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WoodenTableBlockEntity>> WOODEN_TABLE =
            BLOCK_ENTITIES.register("wooden_table",
                    () -> BlockEntityType.Builder.of(WoodenTableBlockEntity::new,
                            ModBlocks.WOODEN_TABLE.get()).build(null));

    private ModBlockEntities() {
    }
}
