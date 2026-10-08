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
 * RTS 正交投影：开着正交时把游戏投影矩阵换成正交矩阵。
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
