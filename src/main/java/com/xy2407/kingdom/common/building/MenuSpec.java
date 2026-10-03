package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 餐厅菜单上的一道菜，对应面板上的一张菜品卡片。
 *
 * <p>和工业配方的区别：成品只有一道（{@code output} 是单条不是数组），
 * 且多一个 {@code price}——餐厅卖的是"这道菜"，价格跟着菜走。</p>
 *
 * @param id     标识，同一建筑内唯一，作面板与入库的键
 * @param name   显示名
 * @param inputs 食材
 * @param output 成品
 * @param price  售价
 */
public record MenuSpec(String id, String name, List<ItemCount> inputs,
                       ItemCount output, int price) {

    public MenuSpec {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        output = output == null ? new ItemCount("", 1) : output;
        price = Math.max(0, price);
    }
}
