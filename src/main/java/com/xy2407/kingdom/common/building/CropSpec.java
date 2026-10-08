package com.xy2407.kingdom.common.building;

/**
 * 农业建筑能种的一种作物，对应农业面板上的一个作物槽位：物品 id 与显示名。
 */
public record CropSpec(String item, String name) {

    public CropSpec {
        item = item == null ? "" : item;
        name = name == null ? "" : name.trim();
    }

    public boolean unnamed() {
        return name.isEmpty() || name.equals(item);
    }
}
