package com.xy2407.kingdom.common.building;

/**
 * 军事建筑的一种驻军编制，对应面板上的一张兵种卡片。
 *
 * <p>和养殖的 {@link AnimalSpec}、工业的 {@link RecipeSpec} 是同一套模式：一座建筑列出
 * 它能编练的兵种，每种各自带上限与开销，面板照清单铺卡片。</p>
 *
 * @param id       标识，同一建筑内唯一，作面板与入库的键
 * @param name     显示名；未配置时取 id
 * @param capacity 编制上限（这一兵种最多招多少人）
 * @param weapon   配发武器物品 id
 * @param armor    配发护甲物品 id
 * @param wage     每人每日军饷
 * @param ranged   是否远程兵种（决定守塔还是出阵）
 */
public record TroopSpec(String id, String name, int capacity, String weapon,
                        String armor, int wage, boolean ranged) {

    public TroopSpec {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        weapon = weapon == null ? "" : weapon;
        armor = armor == null ? "" : armor;
        capacity = Math.max(0, capacity);
        wage = Math.max(0, wage);
    }
}
