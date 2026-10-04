package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.farm.FarmAreaSelector;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 框选农田时按 Esc 只取消框选，不弹暂停菜单
 */
@Mixin(Minecraft.class)
public abstract class FarmPauseGuardMixin {

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void kingdomCancelFarmSelection(boolean pauseOnly, CallbackInfo callback) {
        if (FarmAreaSelector.isActive()) {
            callback.cancel();
            FarmAreaSelector.cancelByEscape();
        }
    }
}
