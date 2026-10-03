package com.xy2407.kingdom.common.building;

import java.util.Locale;

/**
 * 建筑类型。
 *
 * <p>六个类型各自对应 {@code structures} 下的一个子目录（见 {@link #folder()}）。目录名与
 * 蓝图同名的 json 里的 {@code type} 字段共用这套标识，两者互为兜底：写了 json 就听 json，
 * 没写就按所在目录推断。</p>
 */
public enum BuildingType {

    /** 住宅：提供床位。 */
    RESIDENTIAL("住宅", "residential", "housing", "house", "home"),
    /** 农业：种植类生产。 */
    AGRICULTURE("农业", "agriculture", "farm", "farming", "crop"),
    /** 养殖：畜牧类生产。 */
    LIVESTOCK("养殖", "livestock", "ranch", "pasture", "animal"),
    /** 工业：加工制造。 */
    INDUSTRY("工业", "industry", "industrial", "factory", "workshop"),
    /** 商业：交易与服务。 */
    COMMERCIAL("商业", "commercial", "commerce", "shop", "market", "store"),
    /** 军事：防御与驻军。 */
    MILITARY("军事", "military", "army", "barracks", "defense");

    private final String label;
    private final String[] aliases;

    BuildingType(String label, String... aliases) {
        this.label = label;
        this.aliases = aliases;
    }

    /** 中文显示名。 */
    public String label() {
        return label;
    }

    /** 约定的子目录名。 */
    public String folder() {
        return aliases[0];
    }

    /**
     * 解析类型标识：接受枚举名、目录名、常见英文别名与中文显示名，大小写不敏感。
     *
     * @return 无法识别时返回 null（例如放在 {@code structures} 根目录、又没有写 json 的建筑）
     */
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
