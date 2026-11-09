package com.xy2407.kingdom.common.npc;

/**
 * 市民主状态：白天、夜晚、玩家接管与武装战斗，四态互斥。
 */
public enum NpcState {
    DAY,
    NIGHT,
    COMMANDED,
    COMBAT;

    public static NpcState parse(String name) {
        for (NpcState value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return DAY;
    }
}
