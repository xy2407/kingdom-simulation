package com.xy2407.kingdom.common.npc;

import java.util.List;

import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.CropCatalog;
import com.xy2407.kingdom.common.farm.FarmCropCompat;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.server.farm.FarmLandRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农田动作执行：耕地、播种、收割（收割后立即补种）。
 */
final class FarmActionExecutor {

    private FarmActionExecutor() {
    }

    static void apply(ServerLevel level, FarmPlot plot, NpcFarmService.Action action,
                      BlockPos soil, NpcEntity npc) {
        BlockPos cropPos = FarmCropCompat.cropPos(soil, plot.cropId());
        BlockState cropState = level.getBlockState(cropPos);
        if (action == NpcFarmService.Action.HARVEST) {
            if (!isMature(cropState)) {
                return;
            }
            harvest(level, plot, npc, soil, cropPos, cropState);
            return;
        }
        if (action == NpcFarmService.Action.TILL) {
            till(level, soil);
            return;
        }
        plant(level, plot, soil);
    }

    static boolean isMature(BlockState state) {
        return FarmCropCompat.isMature(state);
    }

    private static void harvest(ServerLevel level, FarmPlot plot, NpcEntity npc,
                                BlockPos soil, BlockPos cropPos, BlockState cropState) {
        List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, null, npc, ItemStack.EMPTY);
        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        FarmProduceDeposit.hold(level, plot, cropPos, npc, drops);
        plant(level, plot, soil);
    }

    private static void plant(ServerLevel level, FarmPlot plot, BlockPos soil) {
        Block crop = cropBlock(plot.cropId());
        if (crop == null) {
            return;
        }
        FarmCropCompat.plant(level, soil, crop, plot);
    }

    private static void till(ServerLevel level, BlockPos soil) {
        if (!level.getBlockState(soil).is(BlockTags.DIRT)) {
            return;
        }
        BlockPos above = soil.above();
        if (level.getBlockState(above).is(Blocks.WATER)) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (!level.getBlockState(above).isAir()) {
            return;
        }
        level.setBlock(soil, Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
        FarmLandRegistry.mark(level, soil, DateSystem.totalDay(level));
    }

    private static Block cropBlock(String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return null;
        }
        Block fromCatalog = resolvePlantBlock(CropCatalog.plantBlockId(cropId));
        if (fromCatalog != null) {
            return fromCatalog;
        }
        ResourceLocation key = ResourceLocation.tryParse(cropId);
        if (key != null) {
            if (BuiltInRegistries.BLOCK.containsKey(key)) {
                Block block = BuiltInRegistries.BLOCK.get(key);
                if (block != null && block != Blocks.AIR && CropCatalog.isCropPlant(block)) {
                    return block;
                }
            }
            if (BuiltInRegistries.ITEM.containsKey(key)) {
                Item item = BuiltInRegistries.ITEM.get(key);
                if (item instanceof BlockItem blockItem && CropCatalog.isCropPlant(blockItem.getBlock())) {
                    return blockItem.getBlock();
                }
            }
        }
        return legacyCropBlock(cropId);
    }

    private static Block resolvePlantBlock(String plantBlockId) {
        if (plantBlockId == null || plantBlockId.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(plantBlockId);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block == null || block == Blocks.AIR ? null : block;
    }

    private static Block legacyCropBlock(String cropId) {
        return switch (cropId) {
            case "minecraft:wheat", "minecraft:wheat_seeds" -> Blocks.WHEAT;
            case "minecraft:carrot" -> Blocks.CARROTS;
            case "minecraft:potato" -> Blocks.POTATOES;
            case "minecraft:beetroot", "minecraft:beetroot_seeds" -> Blocks.BEETROOTS;
            case "minecraft:pumpkin", "minecraft:pumpkin_seeds" -> Blocks.PUMPKIN_STEM;
            case "minecraft:melon", "minecraft:melon_seeds" -> Blocks.MELON_STEM;
            case "minecraft:torchflower_seeds" -> Blocks.TORCHFLOWER_CROP;
            case "minecraft:pitcher_pod" -> Blocks.PITCHER_CROP;
            case "minecraft:sugar_cane" -> Blocks.SUGAR_CANE;
            case "minecraft:sweet_berries" -> Blocks.SWEET_BERRY_BUSH;
            case "minecraft:bamboo" -> Blocks.BAMBOO;
            case "minecraft:nether_wart" -> Blocks.NETHER_WART;
            default -> null;
        };
    }
}
