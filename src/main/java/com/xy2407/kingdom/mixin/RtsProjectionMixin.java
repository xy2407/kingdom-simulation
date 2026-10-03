package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RtsModeManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RTS 正交投影：开着正交时把 {@code GameRenderer.getProjectionMatrix(double)} 的返回值换成正交矩阵。
 *
 * <p>渲染投影与视锥剔除共用这个方法，所以换这里等于两处一起生效。从 {@code xy's_nsuk} 的
 * {@code GameRendererProjectionMixin} 迁来，逻辑未变。</p>
 */
@Mixin(GameRenderer.class)
public abstract class RtsProjectionMixin {

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void kingdom$replaceWithOrtho(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (!RtsModeManager.isActive() || !RtsModeManager.isOrthoEnabled()) {
            return;
        }
        double halfWidth = RtsModeManager.getOrthoHalfWidth();
        if (halfWidth <= 0.0D) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float aspect = minecraft != null && minecraft.getWindow() != null
                ? (float) minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getScreenHeight()
                : 1.0F;
        double halfHeight = halfWidth / aspect;
        cir.setReturnValue(new Matrix4f().ortho(
                (float) -halfWidth, (float) halfWidth,
                (float) -halfHeight, (float) halfHeight,
                0.05F, (float) RtsModeManager.getCameraFarClip()
        ));
    }
}
