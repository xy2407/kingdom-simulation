package com.xy2407.kingdom.common.building;

import java.util.Locale;

/**
 * 建筑工种：决定建筑的具体功能与操作面板，每个工种归属一个大分类。
 */
public enum BuildingWork {

    RESIDENCE(BuildingType.RESIDENTIAL, "住宅", "residence", "house", "home"),
    FARM(BuildingType.AGRICULTURE, "农田", "farm", "farming", "crop"),
    RANCH(BuildingType.LIVESTOCK, "养殖栏", "ranch", "pasture", "animal"),
    BARRACKS(BuildingType.MILITARY, "军营", "barracks", "army", "camp"),
    RESTAURANT(BuildingType.COMMERCIAL, "餐厅", "restaurant", "dining", "tavern"),
    SHOP(BuildingType.COMMERCIAL, "商铺", "shop", "store", "market"),
    LUMBER(BuildingType.INDUSTRY, "伐木场", "lumber", "logging", "woodcutting"),
    MINE(BuildingType.INDUSTRY, "矿井", "mine", "mining", "quarry"),
    WORKSHOP(BuildingType.INDUSTRY, "加工坊", "workshop", "processing", "crafting"),
    FORGE(BuildingType.INDUSTRY, "锻造坊", "forge", "smithing", "blacksmith"),
    CHEESE(BuildingType.INDUSTRY, "奶酪坊", "cheese", "dairy", "creamery"),
    BREWERY(BuildingType.INDUSTRY, "酿酒坊", "brewery", "brewing", "distillery"),
    BUILDER_HUT(BuildingType.OTHER, "建筑工棚", "builder_hut", "builder", "workforce"),
    WAREHOUSE(BuildingType.OTHER, "仓库", "warehouse", "storage", "depot"),
    CITY_HALL(BuildingType.OTHER, "市政厅", "city_hall", "town_hall", "government"),
    HOSPITAL(BuildingType.OTHER, "医院", "hospital", "clinic", "infirmary"),
    SCHOOL(BuildingType.OTHER, "学校", "school", "academy", "college");

    private final BuildingType type;
    private final String label;
    private final String[] aliases;

    BuildingWork(BuildingType type, String label, String... aliases) {
        this.type = type;
        this.label = label;
        this.aliases = aliases;
    }

    public BuildingType type() {
        return type;
    }

    public String label() {
        return label;
    }

    public static BuildingWork parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (BuildingWork work : values()) {
            if (work.name().toLowerCase(Locale.ROOT).equals(key) || work.label.equals(trimmed)) {
                return work;
            }
            for (String alias : work.aliases) {
                if (alias.equals(key)) {
                    return work;
                }
            }
        }
        return null;
    }

}
