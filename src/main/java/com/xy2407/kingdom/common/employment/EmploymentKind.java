package com.xy2407.kingdom.common.employment;

import java.util.Locale;

/**
 * 岗位种类：工作建筑、住宅、建筑工队、农田、牧场、矿场、伐木场
 */
public enum EmploymentKind {

    BUILDING("工作建筑"),
    RESIDENCE("住宅"),
    BUILDER("建筑工队"),
    FARM("农田"),
    RANCH("牧场"),
    MINING("矿场"),
    LUMBER("伐木场");

    private final String label;

    EmploymentKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EmploymentKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BUILDING;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return BUILDING;
        }
    }
}
