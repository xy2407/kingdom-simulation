package com.xy2407.kingdom.common.wall;

/**
 * 城墙节点类型：城楼与城门，各自对应一个 NBT 蓝图
 */
public enum WallKind {

    TOWER("wall_tower"),
    GATE("wall_gate");

    private final String blueprint;

    WallKind(String blueprint) {
        this.blueprint = blueprint;
    }

    public String blueprint() {
        return blueprint;
    }

    public static WallKind parse(String raw) {
        if (raw != null) {
            for (WallKind kind : values()) {
                if (kind.name().equalsIgnoreCase(raw)) {
                    return kind;
                }
            }
        }
        return TOWER;
    }
}
