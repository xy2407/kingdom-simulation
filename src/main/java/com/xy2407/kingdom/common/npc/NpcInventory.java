package com.xy2407.kingdom.common.npc;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.Consumer;

/**
 * 市民随身行装：固定 24 格，作为实体物品能力暴露给容器菜单，变化即回调落库。
 */
public class NpcInventory extends ItemStackHandler {

    public static final int SLOT_COUNT = 24;

    private Consumer<NpcInventory> changeListener;

    public NpcInventory() {
        super(SLOT_COUNT);
    }

    public void setChangeListener(Consumer<NpcInventory> listener) {
        this.changeListener = listener;
    }

    @Override
    protected void onContentsChanged(int slot) {
        if (changeListener != null) {
            changeListener.accept(this);
        }
    }

    public void clearSilently() {
        Consumer<NpcInventory> listener = changeListener;
        changeListener = null;
        try {
            for (int slot = 0; slot < getSlots(); slot++) {
                setStackInSlot(slot, ItemStack.EMPTY);
            }
        } finally {
            changeListener = listener;
        }
    }

    public String writeToSnbt(HolderLookup.Provider registries) {
        CompoundTag tag = serializeNBT(registries);
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        return tag.toString();
    }

    public void readFromSnbt(HolderLookup.Provider registries, String snbt) {
        clearSilently();
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            deserializeNBT(registries, tag);
        } catch (Exception invalid) {
            clearSilently();
        }
    }

    public boolean isEmpty() {
        for (int slot = 0; slot < getSlots(); slot++) {
            if (!getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
