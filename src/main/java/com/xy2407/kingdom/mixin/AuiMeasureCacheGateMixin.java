package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.layout.LayoutMeasureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 AUI 布局测量缓存的闸门改成常开，让绘制外的布局求值共享缓存。
 */
@Mixin(value = LayoutMeasureCache.class, remap = false)
public abstract class AuiMeasureCacheGateMixin {

    @Unique
    private static int kingdom$mirrorDepth;

    @Unique
    private static boolean kingdom$armed;

    @Inject(method = "begin", at = @At("TAIL"), remap = false)
    private static void kingdom$onBegin(CallbackInfo ci) {
        kingdom$mirrorDepth++;
        kingdom$armed = true;
    }

    @Inject(method = "end", at = @At("HEAD"), remap = false, cancellable = true)
    private static void kingdom$onEnd(CallbackInfo ci) {
        if (kingdom$armed && kingdom$mirrorDepth <= 1) {
            ci.cancel();
            return;
        }
        if (kingdom$mirrorDepth > 0) {
            kingdom$mirrorDepth--;
        }
    }
}
