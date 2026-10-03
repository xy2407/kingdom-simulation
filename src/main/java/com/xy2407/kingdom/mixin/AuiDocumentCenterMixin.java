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
 * 让 AUI 的屏幕文档在窗口里<b>居中</b>。
 *
 * <p>AUI 的原实现（{@code Document.applyViewport}）把每个屏幕文档的变换偏移
 * 写死为 (0,0)——{@code gui} 模式下文档尺寸等于 GUI 视口，无所谓；
 * 但 {@code mode=fixed + scale=fit} 下文档缩放后比窗口小（比如 1080×810 的
 * 设计稿在 1080×572 的窗口里），就被钉在左上角。这里在 AUI 算完视口后
 * 把偏移补成居中值：输入映射（{@code screenToDocumentPosition}）、
 * tooltip 定位、槽位点击全部消费同一份变换，改这一处即全链路一致。</p>
 *
 * <p>渲染侧的平移由 {@code AuiContainerDocCenterMixin} 配套完成
 * （渲染路径不消费这份偏移，必须单独 translate）。</p>
 */
@Mixin(value = Document.class, remap = false)
public abstract class AuiDocumentCenterMixin {

    @Inject(method = "applyViewport", at = @At("RETURN"), remap = false)
    private void kingdomCenterViewport(boolean relayout, CallbackInfo ci) {
        try {
            Document self = (Document) (Object) this;
            if (self.inWorld) {
                return; // 世界窗口有自己的摆放逻辑，不参与
            }
            ApricityViewport viewport = self.getViewport();
            if (viewport == null) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getWindow() == null) {
                return;
            }
            double guiScale = Math.max(1.0d, minecraft.getWindow().getGuiScale());
            double guiWidth = minecraft.getWindow().getScreenWidth() / guiScale;
            double guiHeight = minecraft.getWindow().getScreenHeight() / guiScale;
            float renderScale = viewport.renderScale();
            double docWidth = viewport.layoutWidth() * renderScale;
            double docHeight = viewport.layoutHeight() * renderScale;
            if (!(docWidth > 0.0d) || !(docHeight > 0.0d)) {
                return;
            }
            // 已占满视口（gui 模式 / 浏览器模式）就不动，保持 AUI 原行为
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
            // 居中是纯增益，绝不干扰原渲染
        }
    }
}
