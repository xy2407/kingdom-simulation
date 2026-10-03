package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.render.Mask;
import com.xy2407.kingdom.client.aui.KingdomAuiStencil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 掐掉 AUI 的 stencil 裁剪路径。
 *
 * <p>{@code Mask.stencilUsable()} 返回 false 后，圆角遮罩走 SCISSOR、forced 遮罩走 NONE，
 * {@code beginStencilIfNeeded()} 不再被触发，渲染期也就不会再调用 AUI 的
 * {@code enableStencil(主目标)} —— 主目标深度格式不切换、帧内不重建深度附件，
 * 同时规避 Fabulous 链深度拷贝的格式错配报错。</p>
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