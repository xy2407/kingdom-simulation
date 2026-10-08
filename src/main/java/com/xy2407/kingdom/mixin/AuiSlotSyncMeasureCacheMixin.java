package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.LayoutMeasureCache;
import com.sighs.apricityui.screen.SlotDataBinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给容器屏的逐槽位坐标同步补上布局测量缓存上下文。
 */
@Mixin(value = SlotDataBinder.class, remap = false)
public abstract class AuiSlotSyncMeasureCacheMixin {

    @Inject(method = "syncAllSlotPositions", at = @At("HEAD"), remap = false)
    private void kingdom$beginMeasureCache(Document document, int leftPos, int topPos, boolean force,
                                           CallbackInfo ci) {
        LayoutMeasureCache.begin();
    }

    @Inject(method = "syncAllSlotPositions", at = @At("TAIL"), remap = false)
    private void kingdom$endMeasureCache(Document document, int leftPos, int topPos, boolean force,
                                         CallbackInfo ci) {
        LayoutMeasureCache.end();
    }
}
