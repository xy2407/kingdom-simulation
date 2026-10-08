package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.Mask;
import com.sighs.apricityui.viewport.ApricityViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * scissor 裁剪框跟随文档的居中偏移，与居中 mixin 配套。
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
