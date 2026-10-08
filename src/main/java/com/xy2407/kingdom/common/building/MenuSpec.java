package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 餐厅菜单上的一道菜，对应面板上的一张菜品卡片：食材、成品与售价。
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
