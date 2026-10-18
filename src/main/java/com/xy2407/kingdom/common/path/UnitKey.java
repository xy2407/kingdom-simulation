package com.xy2407.kingdom.common.path;

/**
 * 单元寻址：维度 + 水平区块 + 垂直层拼成字符串键。
 */
public final class UnitKey {

    private UnitKey() {
    }

    public static String of(String dimension, int cx, int layer, int cz) {
        return dimension + '|' + cx + '|' + layer + '|' + cz;
    }
}
