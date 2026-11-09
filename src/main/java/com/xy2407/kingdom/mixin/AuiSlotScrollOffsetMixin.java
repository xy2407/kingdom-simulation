package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityContainerMenu;
import com.sighs.apricityui.screen.SlotDataBinder;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【已停用 · 2026-11-08】容器屏滚动补偿。
 *
 * <p>AUI 1.3.1 起不再需要它：{@code Slot#getSlotVisualBounds()} 直接返回
 * {@code getBoundingClientRect()}，而后者用 {@code Rect.position = Position.forRender(element)}，
 * 里面已经对每一层祖先减去了 {@code getScrollLeft()/getScrollTop()}。
 * 本 mixin 再减一次会把滚动量算两遍，滚动后槽位的可点击区与画面对不上。
 *
 * <p>结论：**已从 mixins.xy2407_kingdom.json 的 client 列表移除**，不要重新启用。
 * 保留源码只为回溯历史；判断依据见《AUI使用总结》§0.5.2。
 */
@Mixin(value = SlotDataBinder.class, remap = false)
public abstract class AuiSlotScrollOffsetMixin {

    @Accessor(value = "menu", remap = false)
    public abstract ApricityContainerMenu kingdom$menu();

    @Accessor(value = "bindingsByGlobalIndex", remap = false)
    public abstract LinkedHashMap<Integer, ?> kingdom$bindings();

    private static Method kingdom$elementMethod;
    private static Method kingdom$indexMethod;

    @Inject(method = "syncAllSlotPositions", at = @At("RETURN"), remap = false)
    private void kingdom$applyScrollOffset(Document document, int leftPos, int topPos,
                                           boolean force, CallbackInfo ci) {
        try {
            if (document == null || document.inWorld) {
                return;
            }
            Map<Integer, ?> bindings = kingdom$bindings();
            AbstractContainerMenu menu = kingdom$menu();
            if (bindings == null || bindings.isEmpty() || menu == null) {
                return;
            }
            double scale = document.getViewportScaleX();
            if (!(scale > 0.0d)) {
                return;
            }
            for (Object binding : bindings.values()) {
                if (binding == null) {
                    continue;
                }
                Element element = kingdom$elementOf(binding);
                int index = kingdom$indexOf(binding);
                if (element == null || index < 0 || index >= menu.slots.size()) {
                    continue;
                }
                double scrollX = 0.0d;
                double scrollY = 0.0d;
                for (Element ancestor = element.parentElement; ancestor != null;
                     ancestor = ancestor.parentElement) {
                    scrollX += ancestor.getScrollLeft();
                    scrollY += ancestor.getScrollTop();
                }
                if (Math.abs(scrollX) < 0.01d && Math.abs(scrollY) < 0.01d) {
                    continue;
                }
                Slot slot = menu.slots.get(index);
                slot.x -= (int) Math.round(scrollX * scale);
                slot.y -= (int) Math.round(scrollY * scale);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Element kingdom$elementOf(Object binding) {
        try {
            if (kingdom$elementMethod == null) {
                kingdom$elementMethod = binding.getClass().getMethod("slotElement");
            }
            Object value = kingdom$elementMethod.invoke(binding);
            return value instanceof Element element ? element : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static int kingdom$indexOf(Object binding) {
        try {
            if (kingdom$indexMethod == null) {
                kingdom$indexMethod = binding.getClass().getMethod("globalIndex");
            }
            Object value = kingdom$indexMethod.invoke(binding);
            return value instanceof Integer index ? index : -1;
        } catch (Throwable failure) {
            return -1;
        }
    }
}
