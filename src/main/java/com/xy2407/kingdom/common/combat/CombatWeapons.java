package com.xy2407.kingdom.common.combat;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 战斗武器标签判定，主手为标签内武器即认定武装，并区分近战与远程
 */
public final class CombatWeapons {

    public static final TagKey<Item> COMBAT_WEAPON_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "combat_weapons"));

    private CombatWeapons() {
    }

    public static boolean isCombatWeapon(ItemStack stack) {
        return !stack.isEmpty() && stack.is(COMBAT_WEAPON_TAG);
    }

    public static boolean isRanged(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
    }

    public static boolean isMelee(ItemStack stack) {
        return isCombatWeapon(stack) && !isRanged(stack);
    }
}
