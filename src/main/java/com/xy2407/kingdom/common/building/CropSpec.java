package com.xy2407.kingdom.common.building;

/**
 * 农业建筑能种的一种作物，对应农业面板上的一个作物槽位
 *
 * @param item 作物物品 id：既是面板槽位的图标物品，也是农夫决定种下哪个方块的依据
 * @param name 显示名；未配置时取物品 id
 */
public record CropSpec(String item, String name) {

    public CropSpec {
        item = item == null ? "" : item;
        name = name == null ? "" : name.trim();
    }

    /** 显示名是否等于物品 id（等于说明 json 只写了 id，面板可以再查一次默认译名）。 */
    public boolean unnamed() {
        return name.isEmpty() || name.equals(item);
    }
}
