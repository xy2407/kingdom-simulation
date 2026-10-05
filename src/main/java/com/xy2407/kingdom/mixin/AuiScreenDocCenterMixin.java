package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.screen.ApricityScreen;
import com.sighs.apricityui.viewport.ApricityViewport;
import com.xy2407.kingdom.client.aui.AuiDocPlacement;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 纯 DOM 屏文档的<b>画面居中</b>——{@link AuiContainerDocCenterMixin} 的纯 DOM 版。
 *
 * <p><b>为什么必须有这个。</b>{@link AuiDocumentCenterMixin} 注在
 * {@code Document.applyViewport} 上，把居中偏移写进 {@code Document.viewportTransform}，
 * 而那份变换<b>只有输入映射消费</b>（{@code screenToDocumentPosition}、tooltip、点击热区）；
 * 渲染路径不读它，画面平移要在绘制处单独补。</p>
 *
 * <p>容器页那一半由 {@link AuiContainerDocCenterMixin} 注在
 * {@code ApricityContainerScreen.drawLinkedDocument} 上补掉了——所以 {@code backpack.html}
 * 一直正常。纯 DOM 页走的是 {@code ApricityScreen}，<b>此前没有人补这一半</b>，
 * 于是只要 mixin 真的加了偏移，就必然出现"面板钉在文档左上、点击却按居中算"。
 * 两个类是独立继承（{@code ApricityScreen extends Screen}、
 * {@code ApricityContainerScreen extends AbstractContainerScreen}），互不影响，不会双重平移。</p>
 *
 * <p><b>它只对"文档缩放后小于视口"的页面生效</b>（与
 * {@link AuiDocumentCenterMixin} 的短路条件完全一致）：{@code mode=gui} 下文档尺寸恰好等于
 * GUI 视口，两条判断都成立，这里直接 return，画面与输入都不动——所以对
 * {@code start.html} / {@code city-create.html} / {@code buildings/*.html} 这些
 * {@code mode=gui} 的页面是零影响。</p>
 *
 * <p><b>必须在 RETURN 处把平移收回去</b>：AUI 内部 {@code pushPose()/popPose()} 只还原到
 * "平移之后"的状态，平移会泄漏给同一帧里后续所有绘制（伪光标、持久文档等）。</p>
 */
@Mixin(value = ApricityScreen.class, remap = false)
public abstract class AuiScreenDocCenterMixin {

    /** 本帧已施加的居中偏移，供 RETURN 处原样收回。 */
    @Unique
    private static final double[] KINGDOM_SCREEN_OFFSET = new double[2];

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void kingdomCenterScreenDocument(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        KINGDOM_SCREEN_OFFSET[0] = 0.0d;
        KINGDOM_SCREEN_OFFSET[1] = 0.0d;
        try {
            Document document = ((ApricityScreen) (Object) this).getLinkedDocument();
            if (document == null || document.inWorld) {
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
                return; // 已占满（gui / 浏览器模式），保持 AUI 原行为
            }
            double[] offset = AuiDocPlacement.centeredOffset(guiWidth, guiHeight, docWidth, docHeight);
            double offsetX = offset[0];
            double offsetY = offset[1];
            if (offsetX < 0.01d && offsetY < 0.01d) {
                return;
            }
            // 平移在 AUI 自己的 pushPose 之外、随 RETURN 收回：净效果 T(偏移)∘S(缩放)
            guiGraphics.pose().translate(offsetX, offsetY, 0.0d);
            KINGDOM_SCREEN_OFFSET[0] = offsetX;
            KINGDOM_SCREEN_OFFSET[1] = offsetY;
            // 输入映射同步（applyViewport 可能在本帧绘制后又把偏移清零）
            document.setViewportTransform(renderScale, renderScale, offsetX, offsetY);
        } catch (Throwable ignored) {
            // 居中是纯增益，绝不干扰原渲染
        }
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void kingdomRestoreScreenPose(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        double offsetX = KINGDOM_SCREEN_OFFSET[0];
        double offsetY = KINGDOM_SCREEN_OFFSET[1];
        KINGDOM_SCREEN_OFFSET[0] = 0.0d;
        KINGDOM_SCREEN_OFFSET[1] = 0.0d;
        if (offsetX < 0.01d && offsetY < 0.01d) {
            return;
        }
        try {
            // 抵消 HEAD 处的平移，避免偏移泄漏到同帧后续绘制（伪光标 / 持久文档）
            guiGraphics.pose().translate(-offsetX, -offsetY, 0.0d);
        } catch (Throwable ignored) {
            // 收回失败只是画面偏移，绝不能让屏幕崩掉
        }
    }
}
