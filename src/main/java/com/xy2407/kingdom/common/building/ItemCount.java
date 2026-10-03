package com.xy2407.kingdom.common.building;

/**
 * 配方里的一个物品条目：物品 id + 数量。
 *
 * @param item  物品 id，如 {@code minecraft:oak_log}
 * @param count 数量，至少 1
 */
public record ItemCount(String item, int count) {

    public ItemCount {
        item = item == null ? "" : item;
        count = Math.max(1, count);
    }
}
