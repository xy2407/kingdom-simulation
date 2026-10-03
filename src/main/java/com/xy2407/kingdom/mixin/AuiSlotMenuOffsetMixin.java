package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.screen.ApricityContainerMenu;
import com.sighs.apricityui.screen.SlotDataBinder;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 菜单槽位坐标跟随文档的居中偏移（与 AuiDocumentCenterMixin 配套）。
 *
 * <p>{@code SlotDataBinder.syncAllSlotPositions:117-118} 把槽位文档坐标换算成
 * 菜单坐标时用 {@code doc.x * viewportScaleX - leftPos}，不消费文档绘制偏移——
 * AUI 假设文档永远画在 (0,0)。我们的居中 mixin 把 DOM 画面平移之后，
 * 物品渲染位置（leftPos + slot.x）与点击热区仍按未偏移计算，
 * 表现为"槽位渲染与实际槽位严重错位、完全点不到"。</p>
 *
 * <p>修法：sync 结束后给所有菜单槽位补上偏移（GUI 坐标）。
 * 悬停判定（{@code documentPositionAt}）走 {@code screenToDocumentPosition}，
 * 本来就含偏移，不受影响。gui 模式与世界窗口偏移为 0，无影响。</p>
 */
@Mixin(value = SlotDataBinder.class, remap = false)
public abstract class AuiSlotMenuOffsetMixin {

    @Accessor(value = "menu", remap = false)
    public abstract ApricityContainerMenu kingdom$menu();

    @Inject(method = "syncAllSlotPositions", at = @At("RETURN"), remap = false)
    private void kingdomShiftMenuSlots(Document document, int leftPos, int topPos, boolean force, CallbackInfo ci) {
        try {
            if (document == null || document.inWorld) {
                return;
            }
            double renderScale = document.getViewportScaleX();
            if (!(renderScale > 0.0d)) {
                return;
            }
            Position origin = document.documentToGuiPosition(new Position(0.0d, 0.0d));
            if (Math.abs(origin.x) < 0.01d && Math.abs(origin.y) < 0.01d) {
                return;
            }
            AbstractContainerMenu menu = kingdom$menu();
            if (menu == null) {
                return;
            }
            for (Slot slot : menu.slots) {
                slot.x += origin.x;
                slot.y += origin.y;
            }
        } catch (Throwable ignored) {
            // 坐标修正失败只是回到"错位"，绝不能让容器屏崩掉
        }
    }
}