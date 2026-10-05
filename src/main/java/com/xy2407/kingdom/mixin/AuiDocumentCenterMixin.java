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
            // 居中是纯增益，绝不干扰原渲染
        }
    }

    /**
     * 每个页面路径只打一次诊断：把"文档尺寸 / 视口尺寸 / 缩放 / 判定结果"一次摊开，
     * 用来定位"不居中 + 点击偏移"。定位完可以整段删掉。
     */
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

    /** 已诊断过的页面路径，避免每帧刷屏。 */
    @Unique
    private static final java.util.Set<String> KINGDOM_DIAGNOSED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
}
