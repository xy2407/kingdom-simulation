package com.xy2407.kingdom.common.area;

import java.util.Locale;

/**
 * 作业范围类型：伐木范围 / 养殖范围。同一套框选与存储，按类型区分。
 */
public enum PlotAreaKind {

    LUMBER("伐木范围", "lumber"),
    RANCH("养殖范围", "ranch");

    private final String label;
    private final String id;

    PlotAreaKind(String label, String id) {
        this.label = label;
        this.id = id;
    }

    public String label() {
        return label;
    }

    public String id() {
        return id;
    }

    public static PlotAreaKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (PlotAreaKind kind : values()) {
            if (kind.id.equals(value) || kind.name().toLowerCase(Locale.ROOT).equals(value)) {
                return kind;
            }
        }
        return null;
    }

    public static PlotAreaKind byOrdinal(int ordinal) {
        PlotAreaKind[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }
}
