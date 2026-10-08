package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.sighs.apricityui.viewport.ApricityViewport;
import com.xy2407.kingdom.client.aui.AuiDocPlacement;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 容器屏文档的画面居中：绘制前后成对平移，与输入映射保持一致。
 */
@Mixin(value = ApricityContainerScreen.class, remap = false)
public abstract class AuiContainerDocCenterMixin {

    @Unique
    private static final double[] KINGDOM_APPLIED_OFFSET = new double[2];

    @Inject(method = "drawLinkedDocument", at = @At("HEAD"), remap = false)
    private void kingdomCenterLinkedDocument(GuiGraphics guiGraphics, CallbackInfo ci) {
        KINGDOM_APPLIED_OFFSET[0] = 0.0d;
        KINGDOM_APPLIED_OFFSET[1] = 0.0d;
        try {
            Document document = ((ApricityContainerScreen) (Object) this).getLinkedDocument();
            if (document == null) {
                return;
            }
            ApricityViewport viewport = document.getViewport();
            if (viewport == null) {
                return;
            }
            float renderScale = viewport.renderScale();
            double docWidth = viewport.layoutWidth() * renderScale;
            double docHeight = viewport.layoutHeight() * renderScale;
            double guiWidth = guiGraphics.guiWidth();
            double guiHeight = guiGraphics.guiHeight();
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
            guiGraphics.pose().translate(offsetX, offsetY, 0.0d);
            KINGDOM_APPLIED_OFFSET[0] = offsetX;
            KINGDOM_APPLIED_OFFSET[1] = offsetY;
            document.setViewportTransform(renderScale, renderScale, offsetX, offsetY);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "drawLinkedDocument", at = @At("RETURN"), remap = false)
    private void kingdomRestorePoseAfterLinkedDocument(GuiGraphics guiGraphics, CallbackInfo ci) {
        double offsetX = KINGDOM_APPLIED_OFFSET[0];
        double offsetY = KINGDOM_APPLIED_OFFSET[1];
        KINGDOM_APPLIED_OFFSET[0] = 0.0d;
        KINGDOM_APPLIED_OFFSET[1] = 0.0d;
        if (offsetX < 0.01d && offsetY < 0.01d) {
            return;
        }
        try {
            guiGraphics.pose().translate(-offsetX, -offsetY, 0.0d);
        } catch (Throwable ignored) {
        }
    }
}
