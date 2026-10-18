package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块与物品注册表：市政厅方块及其对应的方块物品。
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(ModMain.MOD_ID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ModMain.MOD_ID);

    public static final DeferredBlock<CityHallBlock> CITY_HALL =
            BLOCKS.registerBlock("city_hall", CityHallBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredBlock<BuilderBlock> BUILDER_BLOCK =
            BLOCKS.registerBlock("builder_block", BuilderBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.WOOD));

    public static final DeferredItem<BlockItem> CITY_HALL_ITEM =
            ITEMS.register("city_hall",
                    () -> new BlockItem(CITY_HALL.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> BUILDER_BLOCK_ITEM =
            ITEMS.register("builder_block",
                    () -> new BlockItem(BUILDER_BLOCK.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
