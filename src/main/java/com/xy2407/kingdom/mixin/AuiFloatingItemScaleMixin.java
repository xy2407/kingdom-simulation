package com.xy2407.kingdom.mixin;

import com.sighs.apricityui.element.Slot;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.RenderNode;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.sighs.apricityui.screen.SlotDataBinder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 放大"跟随光标拿着的物品"，让它和槽位图标同尺寸。
 *
 * <p>AUI 的 {@code ApricityContainerScreen.captureFloatingItem} 用
 * {@code RenderNode.ItemNode.positioned(..., 1.0D, ...)} 写死 1 倍画浮动物品，
 * 而本项目的槽位图标靠 {@code --aui-slot-icon-scale} 放大了（背包 3.5、行囊 2.25），
 * 于是拿起东西以后图标突然缩成 16 文档像素（≈7.8 GUI px），明显偏小。</p>
 *
 * <p>修法：在 capture 的 TAIL 把刚加进去的节点换成按倍数放大的节点，并补偿两处几何：
 * ① {@code ItemNode} 的缩放绕局部 {@code (8,8)}（图标中心）——不管位置的话图标会以中心
 * 为轴上下各长 {@code 8×(scale−1)}，观感是"图标往上偏移"，所以把绘制点右下移
 * {@code 8×(scale−1)}，保持 1 倍时的左上角锚点、只往右下长；
 * ② 装饰（数量文字/耐久条）的位移是在放大后的 pose 里应用的，原样传会被再乘一遍
 * {@code scale}（3.5 × −16.45 ≈ −57.6 文档像素），所以必须除以 {@code scale} 抵消，
 * 否则数量数字会飞到图标上方很远。
 * 倍数在"拿起的那一帧"按光标下的槽位元素定标（{@code Slot.resolveIconScale}），
 * 整个拖拽过程保持不变——否则鼠标挪到另一个面板时尺寸会跟着跳。
 * 定标在 {@code render} 开头、carried 为空时清零，所以每次拿起都会重新定标。</p>
 */
@Mixin(value = ApricityContainerScreen.class, remap = false)
public abstract class AuiFloatingItemScaleMixin {

    /** 光标下取不到绑定槽位时的兜底倍数（背包槽位档位）。 */
    @Unique
    private static final double KINGDOM_FALLBACK_FLOATING_SCALE = 3.5d;

    /** 浮动物品固定层级，与 AUI 原值保持一致。 */
    @Unique
    private static final int KINGDOM_FLOATING_Z = 232;

    /** 本次拖拽已定标的倍数；<= 0 表示还没定标。 */
    @Unique
    private static double kingdomFloatingScale = 0.0d;

    @Accessor(value = "floatingItemNodes", remap = false)
    public abstract List<RenderNode.ItemNode> kingdom$floatingItems();

    @Accessor(value = "slotBinder", remap = false)
    public abstract SlotDataBinder kingdom$slotBinder();

    /** 手上没东西时清零，保证下一次拿起会重新按来源面板定标。 */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void kingdomResetFloatingScale(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                           CallbackInfo ci) {
        try {
            AbstractContainerMenu menu = ((ApricityContainerScreen) (Object) this).getMenu();
            if (menu == null || menu.getCarried().isEmpty()) {
                kingdomFloatingScale = 0.0d;
            }
        } catch (Throwable ignored) {
            kingdomFloatingScale = 0.0d;
        }
    }

    @Inject(method = "captureFloatingItem", at = @At("TAIL"), remap = false)
    private void kingdomScaleFloatingItem(ItemStack stack, int relativeX, int relativeY, String overlayText,
                                          CallbackInfo ci) {
        try {
            ApricityContainerScreen screen = (ApricityContainerScreen) (Object) this;
            Document document = screen.getLinkedDocument();
            List<RenderNode.ItemNode> nodes = kingdom$floatingItems();
            if (document == null || nodes == null || nodes.isEmpty()) {
                return;
            }
            int screenX = relativeX + screen.getGuiLeft();
            int screenY = relativeY + screen.getGuiTop();
            if (kingdomFloatingScale <= 0.0d) {
                kingdomFloatingScale = kingdomResolveIconScale(screen, screenX, screenY);
            }
            ItemStack snapshot = stack == null ? ItemStack.EMPTY : stack.copy();
            boolean carriedIcon = !snapshot.isEmpty();
            Position position = document.screenToDocumentPosition(new Position(screenX, screenY));
            Position decoration = document.screenToDocumentPosition(
                    new Position(screenX, screenY + (carriedIcon ? -8 : 0)));
            // ItemNode 的缩放绕局部 (8,8)（图标中心）进行：不动位置的话图标会以中心为轴
            // 上下各长 8×(scale−1)，观感就是"图标往上偏移"。这里把锚点挪回去，
            // 让图标保持 1 倍时左上角的位置、只往右下长——装饰也以这个左上角为原点。
            double anchorShift = 8.0d * (kingdomFloatingScale - 1.0d);
            // 装饰位移是在放大后的 pose 里应用的，必须除以 scale 才落回 1 倍时的位置，
            // 否则数量数字会被再乘一遍放大倍数（3.5 × −16.45 ≈ −57.6），飞到天上。
            double decorationOffset = (decoration.y - position.y) / kingdomFloatingScale;
            // 丢掉 AUI 刚加进去的 1 倍节点，换成同位置、同装饰、放大后的节点
            nodes.remove(nodes.size() - 1);
            nodes.add(RenderNode.ItemNode.positioned(
                    () -> snapshot,
                    position.x + anchorShift,
                    position.y + anchorShift,
                    kingdomFloatingScale,
                    KINGDOM_FLOATING_Z,
                    true,
                    overlayText,
                    decorationOffset,
                    false));
        } catch (Throwable ignored) {
            // 放大纯增益，绝不能让容器屏崩掉
        }
    }

    /** 光标下槽位元素的图标倍数；取不到就退回兜底值。 */
    private double kingdomResolveIconScale(ApricityContainerScreen screen, int mouseX, int mouseY) {
        try {
            SlotDataBinder binder = kingdom$slotBinder();
            AbstractContainerMenu menu = screen.getMenu();
            if (binder == null || menu == null) {
                return KINGDOM_FALLBACK_FLOATING_SCALE;
            }
            int index = screen.findSlotIndexAt(mouseX, mouseY);
            if (index < 0 || index >= menu.slots.size()) {
                return KINGDOM_FALLBACK_FLOATING_SCALE;
            }
            Slot bound = binder.getBoundElement(menu.slots.get(index));
            if (bound == null) {
                return KINGDOM_FALLBACK_FLOATING_SCALE;
            }
            return Math.max(1.0d, bound.resolveIconScale(1.0f));
        } catch (Throwable ignored) {
            return KINGDOM_FALLBACK_FLOATING_SCALE;
        }
    }
}
