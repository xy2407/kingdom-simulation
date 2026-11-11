package com.xy2407.kingdom.common.forge;

import java.util.List;

import com.xy2407.kingdom.common.building.ItemCount;

/**
 * 锻造计划：目标物品递归展开后的全部步骤，以及最终仍需外部供应的基础原料
 */
public record ForgePlan(String target, int requested, List<ForgeStep> steps,
                        List<ItemCount> baseInputs) {

    public ForgePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        baseInputs = baseInputs == null ? List.of() : List.copyOf(baseInputs);
    }
}
