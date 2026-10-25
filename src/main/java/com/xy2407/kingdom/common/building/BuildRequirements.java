package com.xy2407.kingdom.common.building;

/**
 * 建造材料开关：开启时工地必须备齐方块材料才能施工，关闭后恢复免料建造
 */
public final class BuildRequirements {

    private static boolean required = true;

    private BuildRequirements() {
    }

    public static boolean required() {
        return required;
    }

    public static void setRequired(boolean value) {
        required = value;
    }
}
