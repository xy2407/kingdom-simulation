package com.xy2407.kingdom.common.building;

import java.util.Locale;

/**
 * 建筑类型：七个类型各对应 structures 下的一个子目录，与蓝图 json 的 type 字段互为兜底。
 */
public enum BuildingType {

    RESIDENTIAL("住宅", "residential", "housing", "house", "home"),
    AGRICULTURE("农业", "agriculture", "farm", "farming", "crop"),
    LIVESTOCK("养殖", "livestock", "ranch", "pasture", "animal"),
    INDUSTRY("工业", "industry", "industrial", "factory", "workshop"),
    COMMERCIAL("商业", "commercial", "commerce", "shop", "market", "store"),
    MILITARY("军事", "military", "army", "barracks", "defense"),
    STORAGE("仓库", "storage", "warehouse", "depot", "logistics");

    private final String label;
    private final String[] aliases;

    BuildingType(String label, String... aliases) {
        this.label = label;
        this.aliases = aliases;
    }

    public String label() {
        return label;
    }

    public String folder() {
        return aliases[0];
    }

    public static BuildingType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (BuildingType type : values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(key) || type.label.equals(trimmed)) {
                return type;
            }
            for (String alias : type.aliases) {
                if (alias.equals(key)) {
                    return type;
                }
            }
        }
        return null;
    }
}
