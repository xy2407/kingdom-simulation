package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.render.Mask;
import com.xy2407.kingdom.client.aui.KingdomAuiStencil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 掐掉 AUI 的 stencil 裁剪路径，避免主目标深度附件被当帧重建。
 */
@Mixin(value = Mask.class, remap = false)
public abstract class MaskStencilSuppressMixin {

    @Inject(method = "stencilUsable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void kingdom$suppressStencil(CallbackInfoReturnable<Boolean> cir) {
        if (KingdomAuiStencil.isSuppressed()) {
            cir.setReturnValue(false);
        }
    }
}
