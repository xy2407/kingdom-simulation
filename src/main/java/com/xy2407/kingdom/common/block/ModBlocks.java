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
 * 方块与物品注册表：市政厅、建筑工方块、商铺货架与木椅木桌及其方块物品
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
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredBlock<ShopShelfBlock> SHOP_SHELF =
            BLOCKS.registerBlock("shop_shelf", ShopShelfBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredBlock<WoodenChairBlock> WOODEN_CHAIR =
            BLOCKS.registerBlock("wooden_chair", WoodenChairBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredBlock<WoodenTableBlock> WOODEN_TABLE =
            BLOCKS.registerBlock("wooden_table", WoodenTableBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredItem<BlockItem> CITY_HALL_ITEM =
            ITEMS.register("city_hall",
                    () -> new BlockItem(CITY_HALL.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> BUILDER_BLOCK_ITEM =
            ITEMS.register("builder_block",
                    () -> new BlockItem(BUILDER_BLOCK.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> SHOP_SHELF_ITEM =
            ITEMS.register("shop_shelf",
                    () -> new BlockItem(SHOP_SHELF.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> WOODEN_CHAIR_ITEM =
            ITEMS.register("wooden_chair",
                    () -> new BlockItem(WOODEN_CHAIR.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> WOODEN_TABLE_ITEM =
            ITEMS.register("wooden_table",
                    () -> new BlockItem(WOODEN_TABLE.get(), new Item.Properties()));

    public static final DeferredBlock<FeedingTroughBlock> FEEDING_TROUGH =
            BLOCKS.registerBlock("feeding_trough", FeedingTroughBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredItem<BlockItem> FEEDING_TROUGH_ITEM =
            ITEMS.register("feeding_trough",
                    () -> new BlockItem(FEEDING_TROUGH.get(), new Item.Properties()));

    public static final DeferredBlock<MapStandBlock> MAP_STAND =
            BLOCKS.registerBlock("map_stand", MapStandBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.5F)
                            .sound(SoundType.WOOD)
                            .noOcclusion());

    public static final DeferredItem<BlockItem> MAP_STAND_ITEM =
            ITEMS.register("map_stand",
                    () -> new BlockItem(MAP_STAND.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
