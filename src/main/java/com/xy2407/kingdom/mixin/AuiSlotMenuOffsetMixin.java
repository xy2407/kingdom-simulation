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
 * 菜单槽位坐标跟随文档的居中偏移，与居中 mixin 配套。
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
        }
    }
}
