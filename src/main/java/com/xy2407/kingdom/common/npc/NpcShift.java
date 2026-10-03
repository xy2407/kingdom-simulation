package com.xy2407.kingdom.common.npc;

/**
 * 市民班次，决定选用白班或夜班的排班表
 */
public enum NpcShift {
    DAY_SHIFT,
    NIGHT_SHIFT;

    public static NpcShift parse(String name) {
        for (NpcShift value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return DAY_SHIFT;
    }
}
