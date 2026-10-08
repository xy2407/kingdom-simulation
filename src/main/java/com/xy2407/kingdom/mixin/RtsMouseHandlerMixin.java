package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RtsModeManager;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTS 期间不让鼠标转动玩家视角，相机旋转由 RtsModeManager 负责。
 */
@Mixin(MouseHandler.class)
public abstract class RtsMouseHandlerMixin {

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void kingdom$blockPlayerTurnWhileRts(double movement, CallbackInfo ci) {
        if (RtsModeManager.isActive()) {
            ci.cancel();
        }
    }
}
