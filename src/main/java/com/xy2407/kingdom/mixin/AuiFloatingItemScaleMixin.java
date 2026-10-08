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
 * 放大跟随光标拿着的物品，使其与槽位图标同尺寸。
 */
@Mixin(value = ApricityContainerScreen.class, remap = false)
public abstract class AuiFloatingItemScaleMixin {

    @Unique
    private static final double KINGDOM_FALLBACK_FLOATING_SCALE = 3.5d;

    @Unique
    private static final int KINGDOM_FLOATING_Z = 232;

    @Unique
    private static double kingdomFloatingScale = 0.0d;

    @Accessor(value = "floatingItemNodes", remap = false)
    public abstract List<RenderNode.ItemNode> kingdom$floatingItems();

    @Accessor(value = "slotBinder", remap = false)
    public abstract SlotDataBinder kingdom$slotBinder();

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
            double anchorShift = 8.0d * (kingdomFloatingScale - 1.0d);
            double decorationOffset = (decoration.y - position.y) / kingdomFloatingScale;
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
        }
    }

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
