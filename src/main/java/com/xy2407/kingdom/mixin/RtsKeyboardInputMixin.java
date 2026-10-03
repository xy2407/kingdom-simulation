package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RtsModeManager;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTS 期间把键盘输入从玩家身上摘掉。
 *
 * <p>否则 WASD 会一边推相机（我们直接读 GLFW）一边推玩家本体，玩家会自己走掉。
 * 移植自 {@code xy's_nsuk} 的 {@code KeyboardInputMixin}。</p>
 */
@Mixin(KeyboardInput.class)
public abstract class RtsKeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void kingdom$freezePlayerWhileRts(boolean isSprinting, float yBob, CallbackInfo ci) {
        if (!RtsModeManager.isActive()) {
            return;
        }
        this.leftImpulse = 0.0F;
        this.forwardImpulse = 0.0F;
        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.shiftKeyDown = false;
    }
}
