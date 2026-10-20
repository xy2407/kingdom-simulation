package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.viewport.ApricityViewport;
import com.xy2407.kingdom.client.aui.AuiDocPlacement;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 AUI 屏幕文档在窗口里居中：把视口变换的偏移补成居中值。
 */
@Mixin(value = Document.class, remap = false)
public abstract class AuiDocumentCenterMixin {

    @Inject(method = "applyViewport", at = @At("RETURN"), remap = false)
    private void kingdomCenterViewport(boolean relayout, CallbackInfo ci) {
        try {
            Document self = (Document) (Object) this;
            if (self.inWorld) {
                return;
            }
            ApricityViewport viewport = self.getViewport();
            if (viewport == null) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getWindow() == null) {
                return;
            }
            double guiWidth = Math.max(1.0d, minecraft.getWindow().getGuiScaledWidth());
            double guiHeight = Math.max(1.0d, minecraft.getWindow().getGuiScaledHeight());
            float renderScale = viewport.renderScale();
            double docWidth = viewport.layoutWidth() * renderScale;
            double docHeight = viewport.layoutHeight() * renderScale;
            if (!(docWidth > 0.0d) || !(docHeight > 0.0d)) {
                return;
            }
            if (docWidth >= guiWidth - 0.5d && docHeight >= guiHeight - 0.5d) {
                return;
            }
            double[] offset = AuiDocPlacement.centeredOffset(guiWidth, guiHeight, docWidth, docHeight);
            double offsetX = offset[0];
            double offsetY = offset[1];
            if (offsetX < 0.01d && offsetY < 0.01d) {
                return;
            }
            self.setViewportTransform(renderScale, renderScale, offsetX, offsetY);
        } catch (Throwable ignored) {
        }
    }
}
