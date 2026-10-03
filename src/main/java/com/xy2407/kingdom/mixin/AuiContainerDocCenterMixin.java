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
 * 容器屏文档的<b>画面居中</b>（与 {@link AuiDocumentCenterMixin} 配套）。
 *
 * <p>{@code ApricityContainerScreen.drawLinkedDocument} 只做
 * {@code pose.scale(renderScale)} 后从 (0,0) 画——AUI 的渲染路径不消费
 * {@code viewportOffset}，所以光改输入映射画面不动。这里在它绘制前后成对
 * 平移同样的居中偏移，画面与点击/悬停映射保持一致。</p>
 *
 * <p><b>必须在方法 RETURN 处把平移收回去</b>：AUI 内部的 {@code pushPose()/popPose()}
 * 只还原到"平移之后"的状态，平移会<b>泄漏</b>给同一帧里后续所有绘制——
 * tooltip（{@code drawSlotHoverTooltipByElement}）、{@code Cursor.drawPseudoCursor}、
 * {@code Client.drawPersistentScreenDocuments} 会被整体又推一个居中偏移量
 * （现象：对准物品，tooltip 偏出去一大截，偏移量正好等于文档居中偏移）。</p>
 */
@Mixin(value = ApricityContainerScreen.class, remap = false)
public abstract class AuiContainerDocCenterMixin {

    /** 本帧已施加的居中偏移，供 RETURN 处原样收回。 */
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
                return; // 已占满，保持 AUI 原行为
            }
            double[] offset = AuiDocPlacement.centeredOffset(guiWidth, guiHeight, docWidth, docHeight);
            double offsetX = offset[0];
            double offsetY = offset[1];
            if (offsetX < 0.01d && offsetY < 0.01d) {
                return;
            }
            // 平移在 pushPose 之外、随 RETURN 收回：净效果 T(偏移)∘S(缩放)，与旧写法等价
            guiGraphics.pose().translate(offsetX, offsetY, 0.0d);
            KINGDOM_APPLIED_OFFSET[0] = offsetX;
            KINGDOM_APPLIED_OFFSET[1] = offsetY;
            // 输入映射同步（applyViewport 可能在本帧绘制后又把偏移清零）
            document.setViewportTransform(renderScale, renderScale, offsetX, offsetY);
        } catch (Throwable ignored) {
            // 居中是纯增益，绝不干扰原渲染
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
            // 抵消 HEAD 处的平移，避免偏移泄漏到同帧后续绘制（tooltip/伪光标/持久文档）
            guiGraphics.pose().translate(-offsetX, -offsetY, 0.0d);
        } catch (Throwable ignored) {
            // 收回失败只是画面偏移，绝不能让容器屏崩掉
        }
    }
}
