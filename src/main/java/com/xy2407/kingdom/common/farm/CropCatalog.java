package com.xy2407.kingdom.common.farm;

import java.util.List;

import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.CropOption;

/**
 * 可种作物清单：写死的物品 id 列表，同时给界面槽位提供渲染图标用的物品
 */
public final class CropCatalog {

    private static final List<CropOption> CROPS = List.of(
            new CropOption("minecraft:wheat", "小麦"),
            new CropOption("minecraft:carrot", "胡萝卜"),
            new CropOption("minecraft:potato", "马铃薯"),
            new CropOption("minecraft:beetroot", "甜菜根"),
            new CropOption("minecraft:pumpkin", "南瓜"),
            new CropOption("minecraft:melon", "西瓜"),
            new CropOption("minecraft:sugar_cane", "甘蔗"),
            new CropOption("minecraft:nether_wart", "下界疣"));

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
