package com.xy2407.kingdom.common.npc;

/**
 * 市民具体行为，子状态的细分并由排班与饥饿决定；农活拆成种植中 / 收割中 / 田里候着
 */
public enum NpcDetail {
    WORKING_FOCUS(NpcSubState.WORKING),
    SLACKING(NpcSubState.WORKING),
    BUILDING(NpcSubState.WORKING),
    PLANTING(NpcSubState.WORKING),
    HARVESTING(NpcSubState.WORKING),
    FARMING(NpcSubState.WORKING),
    RANCHING(NpcSubState.WORKING),
    SLEEPING(NpcSubState.RESTING),
    WANDERING(NpcSubState.RESTING),
    FORAGING(NpcSubState.HUNGRY),
    GOING_WORK(NpcSubState.MOVING),
    GOING_HOME(NpcSubState.MOVING),
    FOLLOWING(NpcSubState.MOVING),
    DELIVERING(NpcSubState.MOVING),
    COMMANDED_MOVE(NpcSubState.MOVING);

    private final NpcSubState subState;

    NpcDetail(NpcSubState subState) {
        this.subState = subState;
    }

    public NpcSubState subState() {
        return subState;
    }

    public String label() {
        return switch (this) {
            case WORKING_FOCUS -> "上班";
            case SLACKING -> "摸鱼";
            case BUILDING -> "施工";
            case PLANTING -> "播种";
            case HARVESTING -> "收割";
            case FARMING -> "守着农田";
            case RANCHING -> "饲养";
            case SLEEPING -> "睡觉";
            case WANDERING -> "闲逛";
            case FORAGING -> "找吃的";
            case GOING_WORK -> "去上工";
            case GOING_HOME -> "回家";
            case FOLLOWING -> "跟随";
            case DELIVERING -> "送货";
            case COMMANDED_MOVE -> "执行指令";
        };
    }

    public static NpcDetail parse(String name) {
        for (NpcDetail value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return WANDERING;
    }
}
