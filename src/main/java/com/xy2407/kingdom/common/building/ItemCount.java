package com.xy2407.kingdom.common.building;

/**
 * 配方里的一个物品条目：物品 id 与数量（至少 1）。
 */
public record ItemCount(String item, int count) {

    public ItemCount {
        item = item == null ? "" : item;
        count = Math.max(1, count);
    }
}
