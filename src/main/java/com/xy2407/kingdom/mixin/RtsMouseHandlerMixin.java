package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.client.rts.RtsModeManager;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTS 期间不让鼠标转动玩家视角。
 *
 * <p>正常情况下进入 RTS 我们会释放鼠标，鼠标移动走光标、不影响视角；但一旦鼠标被重新抓回
 * （例如中途开过一次 GUI 再关掉），它就会开始转玩家本体。这里兜住这一条。
 * 相机旋转由 {@code RtsModeManager.onMiddleDrag} 负责。</p>
 *
 * <p><b>注意描述符</b>：1.21.1 的 {@code MouseHandler#turnPlayer} 只有**一个 double 参数**
 * （写两个会在启动时抛 {@code InvalidInjectionException: Invalid descriptor}，Mixin 会直接
 * 报出它解析到的真实签名）。这里显式写出 {@code (D)V}，一旦版本改动就立刻失败而不是静默错配。</p>
 */
@Mixin(MouseHandler.class)
public abstract class RtsMouseHandlerMixin {

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void kingdom$blockPlayerTurnWhileRts(double movement, CallbackInfo ci) {
        if (RtsModeManager.isActive()) {
            ci.cancel();
        }
    }
}
