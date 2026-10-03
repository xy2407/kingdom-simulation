package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.LayoutMeasureCache;
import com.sighs.apricityui.screen.SlotDataBinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 AUI 容器屏的"逐槽位坐标同步"补上布局测量缓存上下文。
 *
 * <p><b>问题</b>：{@code SlotDataBinder.syncAllSlotPositions} 在 {@code Screen.init()} 阶段被调用，
 * 此时文档一帧都没画过，{@link LayoutMeasureCache} 没有 active 上下文（{@code depth == 0}），
 * 它的所有读写都被闸门直接放行/丢弃——结果是每个槽位的 {@code Position.of} 都是一次
 * "无缓存全树测量"，且测量结果不落缓存。槽位一多（行装页 24+36=60 个），就是 60 次全树重测，
 * 页面一复杂渲染线程就在布局里自旋到假死（看门狗四份报告：Flex/NormalFlow 深递归 + CPU 猛涨），
 * 而普通复杂度的容器页因为树小感受不到——所以"别人的容器页没事"。</p>
 *
 * <p><b>修法</b>：方法体零重复实现，只在 HEAD 处 {@code begin()}、TAIL 处 {@code end()}，
 * 让 60 次求位共享同一份缓存——与 AUI 绘制路径（{@code Base.drawFlatDocumentInContext}）的
 * 用法完全一致，不改任何缓存失效语义（缓存键自带 {@code layoutDependency} 版本号）。
 * 该方法体内没有提前 return，HEAD/TAIL 必然成对。</p>
 *
 * <p>为什么用 Mixin 而不是改 AUI 源码：AUI 源码是外部依赖，工程规矩不允许改动，
 * 修复一律落在 kingdom 的 {@code com.xy2407.kingdom.mixin} 里（同见 {@code AuiFabulousStencilMixin}）。</p>
 */
@Mixin(value = SlotDataBinder.class, remap = false)
public abstract class AuiSlotSyncMeasureCacheMixin {

    @Inject(method = "syncAllSlotPositions", at = @At("HEAD"), remap = false)
    private void kingdom$beginMeasureCache(Document document, int leftPos, int topPos, boolean force,
                                           CallbackInfo ci) {
        LayoutMeasureCache.begin();
    }

    @Inject(method = "syncAllSlotPositions", at = @At("TAIL"), remap = false)
    private void kingdom$endMeasureCache(Document document, int leftPos, int topPos, boolean force,
                                         CallbackInfo ci) {
        LayoutMeasureCache.end();
    }
}
