package com.xy2407.kingdom.common.building;

import java.util.Locale;

/**
 * 工业建筑子分类：六类各对应蓝图 json 的 industry_kind 字段，决定矿场林场等专属工作逻辑。
 */
public enum IndustryKind {

    LUMBER("伐木", "lumber", "logging", "woodcutting", "wood", "timber"),
    MINING("采矿", "mining", "mine", "quarry", "excavation"),
    PROCESSING("加工", "processing", "process", "crafting", "workshop"),
    FORGE("锻造", "forge", "forging", "smithing", "blacksmith", "smelter"),
    CHEESE("奶酪", "cheese", "dairy", "cheesemaking", "creamery"),
    BREWING("酿酒", "brewing", "brew", "brewery", "wine", "distillery");

    private final String label;

    private final String[] aliases;

    IndustryKind(String label, String... aliases) {
        this.label = label;
        this.aliases = aliases;
    }

    public String label() {
        return label;
    }

    public static IndustryKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (IndustryKind kind : values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(key) || kind.label.equals(trimmed)) {
                return kind;
            }
            for (String alias : kind.aliases) {
                if (alias.equals(key)) {
                    return kind;
                }
            }
        }
        return null;
    }
}
