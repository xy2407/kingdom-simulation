package com.xy2407.kingdom.common.farm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.CropOption;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;

/**
 * 默认作物清单：启动时扫注册表收集全部作物方块，蓝图 json 未写 crops 时用它。
 */
public final class CropCatalog {

    private static final Map<String, CropOption> CROPS = new LinkedHashMap<>();

    private static final Map<String, String> PLANT_BLOCK = new LinkedHashMap<>();

    private static final Map<String, String> SPECIAL_SEEDS = new LinkedHashMap<>();

    private static boolean scanned;

    static {
        SPECIAL_SEEDS.put("vinery:red_grape_seeds", "vinery:red_grape_bush");
        SPECIAL_SEEDS.put("vinery:white_grape_seeds", "vinery:white_grape_bush");
        SPECIAL_SEEDS.put("vinery:savanna_grape_seeds_red", "vinery:savanna_grape_bush_red");
        SPECIAL_SEEDS.put("vinery:savanna_grape_seeds_white", "vinery:savanna_grape_bush_white");
        SPECIAL_SEEDS.put("vinery:taiga_grape_seeds_red", "vinery:taiga_grape_bush_red");
        SPECIAL_SEEDS.put("vinery:taiga_grape_seeds_white", "vinery:taiga_grape_bush_white");
        SPECIAL_SEEDS.put("vinery:jungle_grape_seeds_red", "vinery:jungle_grape_bush_red");
        SPECIAL_SEEDS.put("vinery:jungle_grape_seeds_white", "vinery:jungle_grape_bush_white");
        SPECIAL_SEEDS.put("kaleidoscope_tavern:grapevine", "kaleidoscope_tavern:grape_crop");
        SPECIAL_SEEDS.put("brewery:hops", "brewery:hops_crop");
        SPECIAL_SEEDS.put("kawaiidishes:coffee_berries", "kawaiidishes:coffee_bush");
        SPECIAL_SEEDS.put("culturaldelights:cucumber_seeds", "culturaldelights:cucumbers");
        SPECIAL_SEEDS.put("culturaldelights:eggplant_seeds", "culturaldelights:eggplants");
        SPECIAL_SEEDS.put("culturaldelights:corn_kernels", "culturaldelights:corn");
        SPECIAL_SEEDS.put("farm_and_charm:barley_seeds", "farm_and_charm:barley_crop");
        SPECIAL_SEEDS.put("farm_and_charm:oat_seeds", "farm_and_charm:oat_crop");
        SPECIAL_SEEDS.put("farm_and_charm:strawberry_seeds", "farm_and_charm:strawberry_crop");
        SPECIAL_SEEDS.put("farmersdelight:cabbage_seeds", "farmersdelight:cabbages");
        SPECIAL_SEEDS.put("farmersdelight:onion", "farmersdelight:onions");
        SPECIAL_SEEDS.put("farmersdelight:rice", "farmersdelight:rice");
        SPECIAL_SEEDS.put("kaleidoscope_cookery:tomato_seed", "kaleidoscope_cookery:tomato_crop");
        SPECIAL_SEEDS.put("kaleidoscope_cookery:chili_seed", "kaleidoscope_cookery:chili_crop");
        SPECIAL_SEEDS.put("kaleidoscope_cookery:lettuce_seed", "kaleidoscope_cookery:lettuce_crop");
    }

    private CropCatalog() {
    }

    public static List<CropOption> all() {
        ensureScanned();
        return List.copyOf(CROPS.values());
    }

    public static boolean contains(String id) {
        ensureScanned();
        return id != null && !id.isBlank() && CROPS.containsKey(id);
    }

    public static String labelOf(String id) {
        ensureScanned();
        if (id == null || id.isBlank()) {
            return "";
        }
        CropOption crop = CROPS.get(id);
        return crop == null ? id : crop.label();
    }

    public static String plantBlockId(String seedId) {
        ensureScanned();
        return seedId == null ? null : PLANT_BLOCK.get(seedId);
    }

    public static boolean isCropPlant(Block block) {
        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof NetherWartBlock
                || block instanceof SugarCaneBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof BambooStalkBlock
                || block instanceof CocoaBlock
                || block instanceof CaveVinesBlock
                || block instanceof CaveVinesPlantBlock;
    }

    private static void ensureScanned() {
        if (scanned) {
            return;
        }
        scanned = true;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (isCropPlant(block)) {
                add(block.asItem(), block);
            }
        }
        add(Items.TORCHFLOWER_SEEDS, Blocks.TORCHFLOWER_CROP);
        add(Items.PITCHER_POD, Blocks.PITCHER_CROP);
        add(Items.MELON_SEEDS, Blocks.MELON_STEM);
        add(Items.PUMPKIN_SEEDS, Blocks.PUMPKIN_STEM);
        add(Items.GLOW_BERRIES, Blocks.CAVE_VINES);
        for (Map.Entry<String, String> entry : SPECIAL_SEEDS.entrySet()) {
            addSpecial(entry.getKey(), entry.getValue());
        }
        List<CropOption> sorted = new ArrayList<>(CROPS.values());
        sorted.sort(Comparator.comparing(CropOption::id));
        CROPS.clear();
        for (CropOption crop : sorted) {
            CROPS.put(crop.id(), crop);
        }
    }

    private static void add(Item item, Block block) {
        if (item == null || item == Items.AIR || block == null) {
            return;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (itemId == null || blockId == null) {
            return;
        }
        String id = itemId.toString();
        PLANT_BLOCK.putIfAbsent(id, blockId.toString());
        CROPS.putIfAbsent(id, new CropOption(id, nameOf(item, id)));
    }

    private static void addSpecial(String seedId, String plantBlockId) {
        ResourceLocation seedKey = ResourceLocation.tryParse(seedId);
        ResourceLocation plantKey = ResourceLocation.tryParse(plantBlockId);
        if (seedKey == null || plantKey == null) {
            return;
        }
        if (!BuiltInRegistries.ITEM.containsKey(seedKey) || !BuiltInRegistries.BLOCK.containsKey(plantKey)) {
            return;
        }
        String id = seedKey.toString();
        PLANT_BLOCK.putIfAbsent(id, plantKey.toString());
        CROPS.putIfAbsent(id, new CropOption(id, nameOf(BuiltInRegistries.ITEM.get(seedKey), id)));
    }

    private static String nameOf(Item item, String fallback) {
        if (item == null || item == Items.AIR) {
            return fallback;
        }
        String name = new ItemStack(item).getHoverName().getString();
        return name == null || name.isBlank() ? fallback : name;
    }
}
