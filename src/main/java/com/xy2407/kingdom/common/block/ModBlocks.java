package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
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

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, ModMain.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, ModMain.MOD_ID);

    public static final DeferredBlock<Block> CITY_HALL =
            BLOCKS.register("city_hall", () -> new CityHallBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()));

    public static final DeferredItem<BlockItem> CITY_HALL_ITEM =
            ITEMS.register("city_hall",
                    () -> new BlockItem(CITY_HALL.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
