package com.xy2407.kingdom.common.npc;

/**
 * 市民子状态：昼夜之下的工作、休息、饥饿与移动。
 */
public enum NpcSubState {
    WORKING,
    RESTING,
    HUNGRY,
    MOVING;

    public static NpcSubState parse(String name) {
        for (NpcSubState value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return RESTING;
    }
}
