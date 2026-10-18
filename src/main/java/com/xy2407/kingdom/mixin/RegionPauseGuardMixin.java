package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RegionSelectionState;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 框选区域任务时按 Esc 只取消框选或确认框，不弹暂停菜单
 */
@Mixin(Minecraft.class)
public abstract class RegionPauseGuardMixin {

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void kingdomCancelRegionSelection(boolean pauseOnly, CallbackInfo callback) {
        if (RegionSelectionState.isActive()) {
            callback.cancel();
            RegionSelectionState.cancelByEscape();
        }
    }
}
