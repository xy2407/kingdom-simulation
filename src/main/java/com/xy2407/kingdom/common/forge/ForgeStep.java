package com.xy2407.kingdom.common.forge;

import java.util.List;

import com.xy2407.kingdom.common.building.ItemCount;

/**
 * 锻造步骤：一条配方需要重复多少次，以及每次消耗的原料与产出的物品数量
 */
public record ForgeStep(String key, Kind kind, String recipeId, String output, int outputCount,
                        List<ItemCount> inputs, int ticks, int depth, int runs) {

    public enum Kind {
        CRAFT,
        SMELT
    }

    public ForgeStep {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputCount = Math.max(1, outputCount);
        ticks = Math.max(1, ticks);
        runs = Math.max(1, runs);
    }

    public int totalOutput() {
        return outputCount * runs;
    }
}
