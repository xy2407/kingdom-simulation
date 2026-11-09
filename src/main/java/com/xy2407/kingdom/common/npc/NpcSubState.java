package com.xy2407.kingdom.common.npc;

/**
 * 市民子状态：昼夜之下的工作、休息、饥饿、移动与武装战斗。
 */
public enum NpcSubState {
    WORKING,
    RESTING,
    HUNGRY,
    MOVING,
    FIGHTING;

    public static NpcSubState parse(String name) {
        for (NpcSubState value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return RESTING;
    }
}
