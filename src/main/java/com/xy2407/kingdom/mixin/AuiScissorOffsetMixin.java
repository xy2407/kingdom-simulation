package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.Mask;
import com.sighs.apricityui.viewport.ApricityViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * scissor 裁剪框跟随文档的居中偏移（与 {@link AuiDocumentCenterMixin} 配套）。
 *
 * <p>AUI 的 {@code Mask.enableScissor} 把 CSS 裁剪框按
 * {@code left = x * scissorScale} 直接换算到设备像素，不消费
 * {@code pushScissorScale} 快照里的基础平移（b30/b31）——surface 路径
 * （{@code SurfaceScissorTransform.apply}）用了它，普通屏幕文档路径没用。
 * AUI 自己永远把文档画在 (0,0) 所以无感；我们的居中 mixin 把几何平移之后，
 * 裁剪框留在原地，所有 overflow:hidden 区域（模型盒、槽位面板）
 * 都按旧位置裁切——内容"被切割/被遮住"。</p>
 *
 * <p>修法：入参补上当前上下文文档的偏移（GUI 坐标 ÷ renderScale = CSS 单位）。
 * gui 模式与世界窗口的偏移为 0，完全不影响它们。</p>
 */
@Mixin(value = Mask.class, remap = false)
public abstract class AuiScissorOffsetMixin {

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 0, argsOnly = true, remap = false)
    private static double kingdomShiftScissorX(double x) {
        return x + kingdomDocCssOffset()[0];
    }

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 1, argsOnly = true, remap = false)
    private static double kingdomShiftScissorY(double y) {
        return y + kingdomDocCssOffset()[1];
    }

    /** 当前上下文文档的绘制偏移，换算成 CSS 单位（GUI 偏移 ÷ renderScale）。 */
    private static double[] kingdomDocCssOffset() {
        double[] none = {0.0d, 0.0d};
        try {
            Document document = Document.getContextDocument();
            if (document == null || document.inWorld) {
                return none;
            }
            ApricityViewport viewport = document.getViewport();
            if (viewport == null) {
                return none;
            }
            double renderScale = viewport.renderScale();
            if (!(renderScale > 0.0d)) {
                return none;
            }
            Position origin = document.documentToGuiPosition(new Position(0.0d, 0.0d));
            if (Math.abs(origin.x) < 0.01d && Math.abs(origin.y) < 0.01d) {
                return none;
            }
            return new double[]{origin.x / renderScale, origin.y / renderScale};
        } catch (Throwable ignored) {
            return none;
        }
    }
}
