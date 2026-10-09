package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.viewport.ApricityViewport;
import com.xy2407.kingdom.client.aui.AuiDocPlacement;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
            // 必须与画面侧（AuiScreenDocCenterMixin / AuiContainerDocCenterMixin 用的
            // guiGraphics.guiWidth()）同源：getScreenWidth() 是 GLFW 窗口坐标，高 DPI 下与
            // GUI 视口不等（ApricityViewport 源码对这点有明确警告），两侧口径不一致
            // 就会算出不同偏移 —— 表现为"画在这里、点在别处"。
            double guiWidth = Math.max(1.0d, minecraft.getWindow().getGuiScaledWidth());
            double guiHeight = Math.max(1.0d, minecraft.getWindow().getGuiScaledHeight());
            float renderScale = viewport.renderScale();
            double docWidth = viewport.layoutWidth() * renderScale;
            double docHeight = viewport.layoutHeight() * renderScale;
            if (!(docWidth > 0.0d) || !(docHeight > 0.0d)) {
                return;
            }
            if (docWidth >= guiWidth - 0.5d && docHeight >= guiHeight - 0.5d) {
                kingdomDiagnose(self.getPath(), guiWidth, guiHeight, docWidth, docHeight,
                        renderScale, viewport, "已占满→不居中(保持AUI原行为)", 0.0d, 0.0d);
                return;
            }
            double[] offset = AuiDocPlacement.centeredOffset(guiWidth, guiHeight, docWidth, docHeight);
            double offsetX = offset[0];
            double offsetY = offset[1];
            if (offsetX < 0.01d && offsetY < 0.01d) {
                kingdomDiagnose(self.getPath(), guiWidth, guiHeight, docWidth, docHeight,
                        renderScale, viewport, "偏移≈0→不居中", offsetX, offsetY);
                return;
            }
            self.setViewportTransform(renderScale, renderScale, offsetX, offsetY);
            kingdomDiagnose(self.getPath(), guiWidth, guiHeight, docWidth, docHeight,
                    renderScale, viewport, "★已加居中偏移", offsetX, offsetY);
        } catch (Throwable ignored) {
        }
    }

    private static void kingdomDiagnose(String path, double guiWidth, double guiHeight,
                                        double docWidth, double docHeight, float renderScale,
                                        ApricityViewport viewport, String verdict,
                                        double offsetX, double offsetY) {
        if (path == null || !KINGDOM_DIAGNOSED.add(path)) {
            return;
        }
        com.xy2407.kingdom.ModMain.LOGGER.info(
                "[AUI诊断] {} | gui={}x{} doc={}x{} layout={}x{} renderScale={} | {} offset=({},{})",
                path, guiWidth, guiHeight, docWidth, docHeight,
                viewport.layoutWidth(), viewport.layoutHeight(), renderScale,
                verdict, offsetX, offsetY);
    }

    @Unique
    private static final java.util.Set<String> KINGDOM_DIAGNOSED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
}
