package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RtsModeManager;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTS 相机覆盖：在 {@code Camera.setup} 末尾改写相机位置与旋转，用渲染插值保证平滑。
 *
 * <p>从 {@code xy's_nsuk} 的 {@code CameraMixin} 迁来，逻辑未变（只换了 RtsModeManager 的包名）。</p>
 */
@Mixin(Camera.class)
public abstract class RtsCameraMixin {

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("RETURN"))
    private void kingdom$overrideRtsCamera(
            BlockGetter level, Entity focusedEntity,
            boolean detached, boolean mirrored,
            float partialTicks, CallbackInfo ci) {
        if (!RtsModeManager.isActive()) {
            return;
        }
        Vec3 pos = RtsModeManager.getRenderCameraPos(partialTicks);
        if (pos == null) {
            return;
        }
        setPosition(pos);
        setRotation(
                RtsModeManager.getRenderCameraYaw(partialTicks),
                RtsModeManager.getRenderCameraPitch(partialTicks)
        );
    }
}
