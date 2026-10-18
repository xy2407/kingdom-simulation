package com.xy2407.kingdom.common.npc;

import java.util.Collections;
import java.util.List;

import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.farm.CropCatalog;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.path.SnapshotRepository;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 农田动作执行：耕地、播种、收割（收割后立即补种）。
 */
final class FarmActionExecutor {

    private FarmActionExecutor() {
    }

    static void apply(ServerLevel level, FarmPlot plot, NpcFarmService.Action action,
                      BlockPos soil, NpcEntity npc) {
        BlockPos cropPos = soil.above();
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
        plant(level, plot, soil, cropPos, cropState);
    }

    static boolean isMature(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.getValue(age) >= Collections.max(age.getPossibleValues());
            }
        }
        return false;
    }

    private static void harvest(ServerLevel level, FarmPlot plot, NpcEntity npc,
                                BlockPos soil, BlockPos cropPos, BlockState cropState) {
        List<ItemStack> drops = Block.getDrops(cropState, level, cropPos, null, npc, ItemStack.EMPTY);
        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        markSnapshot(level, cropPos);
        FarmProduceDeposit.hold(level, plot, cropPos, npc, drops);
        plant(level, plot, soil, cropPos, level.getBlockState(cropPos));
    }

    private static void plant(ServerLevel level, FarmPlot plot, BlockPos soil, BlockPos cropPos,
                              BlockState cropState) {
        if (!cropState.isAir()) {
            return;
        }
        Block crop = cropBlock(plot.cropId());
        if (crop == null) {
            return;
        }
        BlockState planted = crop.defaultBlockState();
        if (!planted.canSurvive(level, cropPos)) {
            return;
        }
        level.setBlock(cropPos, planted, Block.UPDATE_ALL);
        markSnapshot(level, cropPos);
    }

    private static void till(ServerLevel level, BlockPos soil) {
        if (!level.getBlockState(soil).is(BlockTags.DIRT)) {
            return;
        }
        if (!level.getBlockState(soil.above()).isAir()) {
            return;
        }
        level.setBlock(soil, Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
        FarmLandRegistry.mark(level, soil, DateSystem.totalDay(level));
        markSnapshot(level, soil);
    }

    private static void markSnapshot(ServerLevel level, BlockPos pos) {
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.markDirty(level, pos);
        }
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
