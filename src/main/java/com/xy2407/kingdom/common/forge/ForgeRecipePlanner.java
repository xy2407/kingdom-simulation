package com.xy2407.kingdom.common.forge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.common.building.ItemCount;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 锻造配方解析：先扣掉仓库现有材料，再沿原版配方递归展开缺口，汇总仍要外部供应的原料
 */
public final class ForgeRecipePlanner {

    private static final int MAX_DEPTH = 6;

    private static final int CRAFT_TICKS = 10;

    private ForgeRecipePlanner() {
    }

    public static ForgePlan plan(ServerLevel level, String targetId, int count,
                                 Map<String, Integer> available) {
        if (level == null || targetId == null || targetId.isBlank() || count <= 0) {
            return new ForgePlan(targetId == null ? "" : targetId, Math.max(1, count),
                    List.of(), List.of());
        }
        Map<String, Integer> stock = new HashMap<>();
        if (available != null) {
            for (Map.Entry<String, Integer> entry : available.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                stock.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, ForgeStep> defs = new LinkedHashMap<>();
        Map<String, Integer> runs = new LinkedHashMap<>();
        Map<String, Integer> bases = new LinkedHashMap<>();
        expand(level, targetId, count, 0, new LinkedHashSet<>(), defs, runs, bases, stock, false);
        List<ForgeStep> steps = new ArrayList<>(defs.size());
        for (Map.Entry<String, ForgeStep> entry : defs.entrySet()) {
            ForgeStep step = entry.getValue();
            steps.add(new ForgeStep(step.key(), step.kind(), step.recipeId(), step.output(),
                    step.outputCount(), step.inputs(), step.ticks(), step.depth(),
                    runs.getOrDefault(entry.getKey(), step.runs())));
        }
        steps.sort(Comparator.comparingInt(ForgeStep::depth).reversed());
        List<ItemCount> baseInputs = new ArrayList<>(bases.size());
        for (Map.Entry<String, Integer> entry : bases.entrySet()) {
            baseInputs.add(new ItemCount(entry.getKey(), entry.getValue()));
        }
        return new ForgePlan(targetId, count, steps, baseInputs);
    }

    private static void expand(ServerLevel level, String itemId, int needed, int depth,
                               Set<String> path, Map<String, ForgeStep> defs,
                               Map<String, Integer> runs, Map<String, Integer> bases,
                               Map<String, Integer> stock, boolean useStock) {
        if (needed <= 0) {
            return;
        }
        if (useStock) {
            int owned = stock.getOrDefault(itemId, 0);
            if (owned > 0) {
                int used = Math.min(owned, needed);
                stock.put(itemId, owned - used);
                needed -= used;
                if (needed <= 0) {
                    return;
                }
            }
        }
        if (depth >= MAX_DEPTH || path.contains(itemId)) {
            bases.merge(itemId, needed, Integer::sum);
            return;
        }
        RecipeHolder<?> holder = findRecipe(level, itemId);
        if (holder == null) {
            bases.merge(itemId, needed, Integer::sum);
            return;
        }
        ItemStack result = resultOf(holder.value(), level);
        int outputCount = Math.max(1, result.getCount());
        int times = (needed + outputCount - 1) / outputCount;
        ForgeStep.Kind kind = holder.value() instanceof AbstractCookingRecipe
                ? ForgeStep.Kind.SMELT : ForgeStep.Kind.CRAFT;
        int ticks = kind == ForgeStep.Kind.SMELT
                ? ((AbstractCookingRecipe) holder.value()).getCookingTime() : CRAFT_TICKS;
        List<ItemCount> inputs = inputsOf(holder.value(), level, stock);
        if (inputs.isEmpty()) {
            bases.merge(itemId, needed, Integer::sum);
            return;
        }
        String recipeId = holder.id().toString();
        String key = kind.name() + ":" + recipeId;
        if (defs.containsKey(key)) {
            runs.merge(key, times, Integer::sum);
        } else {
            defs.put(key, new ForgeStep(key, kind, recipeId, itemId, outputCount, inputs, ticks,
                    depth, times));
            runs.put(key, times);
        }
        Set<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(itemId);
        for (ItemCount input : inputs) {
            expand(level, input.item(), input.count() * times, depth + 1, nextPath, defs, runs,
                    bases, stock, true);
        }
    }

    private static RecipeHolder<?> findRecipe(ServerLevel level, String itemId) {
        RecipeHolder<?> cooking = null;
        int bestTicks = Integer.MAX_VALUE;
        RecipeHolder<?> crafting = null;
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!itemId.equals(itemIdOf(resultOf(recipe, level)))) {
                continue;
            }
            if (recipe instanceof AbstractCookingRecipe cook) {
                RecipeType<?> type = cook.getType();
                if (type != RecipeType.SMELTING && type != RecipeType.BLASTING) {
                    continue;
                }
                if (cook.getCookingTime() < bestTicks) {
                    bestTicks = cook.getCookingTime();
                    cooking = holder;
                }
                continue;
            }
            if (recipe.getType() == RecipeType.CRAFTING && crafting == null) {
                crafting = holder;
            }
        }
        return cooking != null ? cooking : crafting;
    }

    private static ItemStack resultOf(Recipe<?> recipe, ServerLevel level) {
        try {
            return recipe.getResultItem(level.registryAccess());
        } catch (RuntimeException failure) {
            return ItemStack.EMPTY;
        }
    }

    private static List<ItemCount> inputsOf(Recipe<?> recipe, ServerLevel level,
                                            Map<String, Integer> stock) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] candidates = ingredient.getItems();
            if (candidates.length == 0) {
                continue;
            }
            ItemStack pick = candidates[0];
            int bestStock = stock.getOrDefault(itemIdOf(pick), 0);
            for (ItemStack candidate : candidates) {
                int have = stock.getOrDefault(itemIdOf(candidate), 0);
                if (have > bestStock) {
                    bestStock = have;
                    pick = candidate;
                }
            }
            merged.merge(itemIdOf(pick), 1, Integer::sum);
        }
        List<ItemCount> inputs = new ArrayList<>(merged.size());
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            inputs.add(new ItemCount(entry.getKey(), entry.getValue()));
        }
        return inputs;
    }

    private static String itemIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
