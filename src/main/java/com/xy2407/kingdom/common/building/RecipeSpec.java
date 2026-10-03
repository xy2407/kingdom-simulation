package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 工业建筑的一个生产配方，对应面板上的一张配方卡片。
 *
 * <p>一座工业建筑同时只跑一个配方，玩家在面板里从 {@code recipes} 清单里挑一张，
 * 所以这里的每一项都是"可选项"而不是"同时进行的工序"。</p>
 *
 * @param id      标识，同一建筑内唯一，作面板与入库的键
 * @param name    显示名
 * @param inputs  消耗的物品，可为空（无中生有的工序）
 * @param outputs 产出的物品，不可为空——没有产出的配方会被加载器丢掉
 * @param ticks   一次生产耗时（tick）
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
