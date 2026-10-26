package com.xy2407.kingdom.common.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

/**
 * 燃料分流：燃料格放碗时把生食材改判为汤类配方，否则保持原版产物。
 */
public final class FurnaceSoupRecipes {

    private FurnaceSoupRecipes() {
    }

    public static RecipeHolder<?> pick(Level level, AbstractFurnaceBlockEntity furnace,
                                       RecipeHolder<?> fallback) {
        if (level == null || furnace == null || furnace instanceof BlastFurnaceBlockEntity) {
            return fallback;
        }
        ItemStack input = furnace.getItem(0);
        if (input.isEmpty()) {
            return fallback;
        }
        Item raw = input.getItem();
        if (raw != Items.BEETROOT && raw != Items.BROWN_MUSHROOM
                && raw != Items.RED_MUSHROOM && raw != Items.RABBIT) {
            return fallback;
        }
        boolean bowl = furnace.getItem(1).is(Items.BOWL);
        Item wanted;
        if (raw == Items.BEETROOT) {
            wanted = bowl ? Items.BEETROOT_SOUP : null;
        } else if (raw == Items.BROWN_MUSHROOM || raw == Items.RED_MUSHROOM) {
            wanted = bowl ? Items.MUSHROOM_STEW : null;
        } else {
            wanted = bowl ? Items.RABBIT_STEW : Items.COOKED_RABBIT;
        }
        if (wanted == null) {
            return null;
        }
        boolean smoker = furnace instanceof SmokerBlockEntity;
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) {
                continue;
            }
            if (smoker ? cooking.getType() != RecipeType.SMOKING
                    : cooking.getType() != RecipeType.SMELTING) {
                continue;
            }
            if (!cooking.matches(recipeInput, level)) {
                continue;
            }
            if (!cooking.assemble(recipeInput, level.registryAccess()).is(wanted)) {
                continue;
            }
            return holder;
        }
        return wanted == Items.COOKED_RABBIT ? fallback : null;
    }

    public static boolean isSoupIngredient(Item item) {
        return item == Items.BEETROOT || item == Items.BROWN_MUSHROOM
                || item == Items.RED_MUSHROOM || item == Items.RABBIT;
    }

    public static boolean isSoupResult(ItemStack stack) {
        return stack.is(Items.BEETROOT_SOUP) || stack.is(Items.MUSHROOM_STEW)
                || stack.is(Items.RABBIT_STEW);
    }
}
