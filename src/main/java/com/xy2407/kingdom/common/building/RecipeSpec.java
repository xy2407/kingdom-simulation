package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 工业建筑的一个生产配方，对应面板上的一张配方卡片：消耗物品、产出物品与耗时。
 */
public record RecipeSpec(String id, String name, List<ItemCount> inputs,
                         List<ItemCount> outputs, int ticks) {

    public RecipeSpec {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        ticks = Math.max(1, ticks);
    }
}
