package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.common.item.FurnaceSoupRecipes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 熔炉分流注入：
 * ① 替换熔炉选中的配方（碗做燃料时出汤，否则原版产物）；
 * ② 总烹饪时长返回当前配方的值，燃料格变化时同步刷新；
 * ③ 点火时不消耗木碗（碗不当燃料烧），出锅时才扣除一个木碗。
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceBowlRecipeMixin {

    @Shadow
    private int cookingTotalTime;

    @Shadow
    private static int getTotalCookTime(Level level, AbstractFurnaceBlockEntity furnace) {
        throw new AssertionError();
    }

    @ModifyVariable(method = "serverTick", at = @At("STORE"))
    private static RecipeHolder<?> kingdomPickSoupRecipe(RecipeHolder<?> holder, Level level,
                                                         BlockPos pos, BlockState state,
                                                         AbstractFurnaceBlockEntity furnace) {
        return FurnaceSoupRecipes.pick(level, furnace, holder);
    }

    @Inject(method = "getTotalCookTime", at = @At("RETURN"), cancellable = true)
    private static void kingdomSoupCookTime(Level level, AbstractFurnaceBlockEntity furnace,
                                            CallbackInfoReturnable<Integer> callback) {
        RecipeHolder<?> holder = FurnaceSoupRecipes.pick(level, furnace, null);
        if (holder != null && holder.value() instanceof AbstractCookingRecipe cooking) {
            callback.setReturnValue(cooking.getCookingTime());
        }
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void kingdomRefreshTotalTime(int index, ItemStack stack, CallbackInfo callback) {
        if (index != 1) {
            return;
        }
        Level level = ((AbstractFurnaceBlockEntity) (Object) this).getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        this.cookingTotalTime = getTotalCookTime(level, (AbstractFurnaceBlockEntity) (Object) this);
    }

    @Redirect(method = "serverTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private static void kingdomKeepBowl(ItemStack stack, int amount, Level level, BlockPos pos,
                                        BlockState state, AbstractFurnaceBlockEntity furnace) {
        if (!(stack.is(Items.BOWL)
                && FurnaceSoupRecipes.isSoupIngredient(furnace.getItem(0).getItem()))) {
            stack.shrink(amount);
        }
    }

    @Inject(method = "burn", at = @At("RETURN"))
    private static void kingdomConsumeBowl(RegistryAccess registryAccess, RecipeHolder<?> holder,
                                           NonNullList<ItemStack> inventory, int maxStackSize,
                                           AbstractFurnaceBlockEntity furnace,
                                           CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) {
            return;
        }
        ItemStack fuel = inventory.get(1);
        if (fuel.is(Items.BOWL) && FurnaceSoupRecipes.isSoupResult(inventory.get(2))) {
            fuel.shrink(1);
        }
    }
}
