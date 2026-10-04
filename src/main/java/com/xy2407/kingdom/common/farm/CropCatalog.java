package com.xy2407.kingdom.common.farm;

import java.util.List;

import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.CropOption;

/**
 * 默认作物清单：农业建筑 json 没写 crops 时用它，物品 id 同时用于界面图标与种植判定
 */
public final class CropCatalog {

    private static final List<CropOption> CROPS = List.of(
            new CropOption("minecraft:wheat", "小麦"),
            new CropOption("minecraft:carrot", "胡萝卜"),
            new CropOption("minecraft:potato", "马铃薯"),
            new CropOption("minecraft:beetroot", "甜菜根"),
            new CropOption("minecraft:pumpkin", "南瓜"),
            new CropOption("minecraft:melon", "西瓜"),
            new CropOption("minecraft:torchflower_seeds", "火把花"),
            new CropOption("minecraft:pitcher_pod", "瓶子草"),
            new CropOption("minecraft:sugar_cane", "甘蔗"),
            new CropOption("minecraft:sweet_berries", "甜浆果"),
            new CropOption("minecraft:bamboo", "竹子"));

    private CropCatalog() {
    }

    public static List<CropOption> all() {
        return CROPS;
    }

    public static boolean contains(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (CropOption crop : CROPS) {
            if (crop.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static String labelOf(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        for (CropOption crop : CROPS) {
            if (crop.id().equals(id)) {
                return crop.label();
            }
        }
        return id;
    }
}
