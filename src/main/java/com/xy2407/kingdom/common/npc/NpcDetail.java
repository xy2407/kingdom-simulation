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

    public static NpcDetail parse(String name) {
        for (NpcDetail value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return WANDERING;
    }
}
