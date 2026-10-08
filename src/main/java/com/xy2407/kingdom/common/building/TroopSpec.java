package com.xy2407.kingdom.common.building;

/**
 * 军事建筑的一种驻军编制，对应面板上的一张兵种卡片：武器、护甲与军饷。
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
