package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.neoforge.RenderService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 掐掉 AUI 每 tick 一次的 Fabulous 链 stencil 对账
 *
 * <p>{@code RenderService.reconcileFabulousChainStencil()}（neoforge-1.21.1 平台层，由
 * {@code Client.tick} 每 tick 无条件调用，不受 {@code Mask.stencilUsable()} 影响）做两件事：
 * 用原版 {@code RenderTarget.enableStencil()} 给主目标开 stencil（深度格式是原版的
 * {@code GL_DEPTH24_STENCIL8}），紧接着 {@code enableFabulousChainStencil()} 把 Fabulous 的
 * 五个链目标手动重分配成 {@code GL_DEPTH32F_STENCIL8}。两侧格式不一致，MC 每帧把主目标深度
 * 拷进链目标的 {@code glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)} 就会报
 * {@code GL_INVALID_OPERATION "Depth formats do not match"} 并静默失败，日志被刷爆。</p>
 *
 * <p>AUI 本来是靠"某次真的推 stencil 遮罩"（{@code RenderService.enableStencil}，它会把主目标
 * 和链目标一起转成 32F）来对齐格式的。但建筑预览期间我们把 {@code Mask.stencilUsable()} 压成
 * false（见 {@link MaskStencilSuppressMixin}），那条对齐路径不会发生，于是这个每 tick 的
 * 链侧转换会把"主 24S / 链 32F"的不匹配永久维持下去——这正是按 Tab 后日志开始刷屏的原因。</p>
 *
 * <p>掐掉它之后：链目标保持原版格式，与主目标一致；万一真的需要 stencil 了，
 * {@code enableStencil} 仍会把主目标与链目标一起转成 32F，依然自洽。</p>
 *
 * <p>副作用：AUI 失去了"帧前预装 stencil"这一步。1.21.1 平台的 {@code enableStencil} 对主目标
 * 只重分配深度纹理、不重建整个目标，所以不会出现它注释里担心的"帧内重建导致黑闪"。</p>
 */
@Mixin(value = RenderService.class, remap = false)
public abstract class AuiFabulousStencilMixin {

    @Inject(method = "reconcileFabulousChainStencil", at = @At("HEAD"), cancellable = true, remap = false)
    private void kingdom$skipChainReconcile(CallbackInfo ci) {
        ci.cancel();
    }
}
