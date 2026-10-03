package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.layout.LayoutMeasureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 AUI 布局测量缓存的闸门改成"常开"。
 *
 * <p><b>问题</b>：{@code LayoutMeasureCache} 的所有读写都带
 * {@code state.depth <= 0 → 直接放弃} 的闸门，而 {@code begin()/end()} 只在文档绘制时成对出现。
 * 于是绘制之外的每一次布局求值都是"无缓存全树测量"：</p>
 * <ul>
 *   <li>容器屏 {@code init} 阶段逐槽位 {@code Position.of}（24+36=60 次，见
 *       {@link AuiSlotSyncMeasureCacheMixin}——那个 mixin 只是给了 init 一层临时上下文）；</li>
 *   <li><b>鼠标悬停/拖拽命中</b>：AUI 往 {@code AbstractContainerScreen.isHovering} 里注入了
 *       {@code isBoundElementHovered}，鼠标每动一下就对 60 个绑定槽位各调一次
 *       {@code Slot.containsSlotPoint → Position.of}——实测直接把渲染线程卡死
 *       （看门狗 2026-10-01 00:42 报告：栈底是 mouseDragged → findSlot → Position.of）。</li>
 * </ul>
 *
 * <p><b>修法</b>：第一次 {@code begin()} 之后不再允许 {@code end()} 把 {@code depth} 减回 0
 * ——闸门从此常开，绘制内/外的所有布局求值共享同一份缓存。这样做的安全性由缓存键本身保证：
 * 键里带 {@code layoutDependency} 版本号，样式/内容一变自动失效，不存在陈旧命中；
 * 容量也有界（参数化条目 4096 上限，其余是 WeakHashMap）。</p>
 *
 * <p>副作用说明：武装后 {@code depth} 在 1↔2 之间震荡（绘制 begin +1、end -1），
 * 不会无限增长；未武装时的一切行为与原版完全一致。</p>
 */
@Mixin(value = LayoutMeasureCache.class, remap = false)
public abstract class AuiMeasureCacheGateMixin {

    /** 我们镜像的 depth（不含被 cancel 掉的 end）。 */
    @Unique
    private static int kingdom$mirrorDepth;

    @Unique
    private static boolean kingdom$armed;

    @Inject(method = "begin", at = @At("TAIL"), remap = false)
    private static void kingdom$onBegin(CallbackInfo ci) {
        kingdom$mirrorDepth++;
        kingdom$armed = true;
    }

    @Inject(method = "end", at = @At("HEAD"), remap = false, cancellable = true)
    private static void kingdom$onEnd(CallbackInfo ci) {
        // 武装后，最后一个"底"不允许被减掉：depth 永远 >= 1，闸门常开。
        if (kingdom$armed && kingdom$mirrorDepth <= 1) {
            ci.cancel();
            return;
        }
        if (kingdom$mirrorDepth > 0) {
            kingdom$mirrorDepth--;
        }
    }
}
