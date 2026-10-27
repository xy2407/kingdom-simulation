package com.xy2407.kingdom.common.npc;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.Consumer;

/**
 * 市民随身行装：24 格背包 + 主手/副手/四件护甲，变化即回调落库并刷新装备。
 */
public class NpcInventory extends ItemStackHandler {

    public static final int BAG_SLOTS = 24;

    public static final int MAIN_HAND = 24;

    public static final int OFF_HAND = 25;

    public static final int HEAD = 26;

    public static final int CHEST = 27;

    public static final int LEGS = 28;

    public static final int FEET = 29;

    public static final int SLOT_COUNT = 30;

    private Consumer<NpcInventory> changeListener;

    public NpcInventory() {
        super(SLOT_COUNT);
    }

    public void setChangeListener(Consumer<NpcInventory> listener) {
        this.changeListener = listener;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case HEAD -> fits(stack, EquipmentSlot.HEAD);
            case CHEST -> fits(stack, EquipmentSlot.CHEST);
            case LEGS -> fits(stack, EquipmentSlot.LEGS);
            case FEET -> fits(stack, EquipmentSlot.FEET);
            default -> true;
        };
    }

    private static boolean fits(ItemStack stack, EquipmentSlot slot) {
        return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        CompoundTag fixed = tag.copy();
        fixed.putInt("Size", SLOT_COUNT);
        super.deserializeNBT(registries, fixed);
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
