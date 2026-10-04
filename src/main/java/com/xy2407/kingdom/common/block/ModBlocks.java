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

/** 方块与物品注册表。 */
public final class ModBlocks {

    /* 用 DeferredRegister.Blocks / Items 这两个专门子类。
       它们的 registerBlock / register 直接返回 DeferredBlock / DeferredItem，
       而父类 DeferredRegister<Block> 的 register 只返回 DeferredHolder——
       声明成 DeferredBlock 就会报"类型变量 I 的实例不存在"。 */
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

    public static final DeferredItem<BlockItem> CITY_HALL_ITEM =
            ITEMS.register("city_hall",
                    () -> new BlockItem(CITY_HALL.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
