package com.xy2407.kingdom.common.npc;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.items.ItemStackHandler;

import com.xy2407.kingdom.common.combat.CombatWeapons;

import java.util.function.Consumer;

/**
 * 市民随身行装：24 格背包 + 主手/副手/四件护甲，变化即回调落库并刷新装备。
 */
public class NpcInventory extends ItemStackHandler {

    /**
     * 槽位布局（与界面 slot-index 一一对应）：
     * <pre>
     *   0        主手
     *   1        副手
     *   2-5      头盔 / 胸甲 / 护腿 / 靴子
     *   6-29     背包 24 格
     * </pre>
     */
    public static final int MAIN_HAND = 0;

    public static final int OFF_HAND = 1;

    public static final int HEAD = 2;

    public static final int CHEST = 3;

    public static final int LEGS = 4;

    public static final int FEET = 5;

    /** 背包第一格。 */
    public static final int BAG_START = 6;

    public static final int BAG_SLOTS = 24;

    public static final int SLOT_COUNT = BAG_START + BAG_SLOTS;

    /** 库存 SNBT 的布局版本：老存档（背包 0-23、主手 24……）读进来要重映射。 */
    private static final String KEY_LAYOUT = "SlotLayout";
    private static final int LAYOUT_VERSION = 2;

    private Consumer<NpcInventory> changeListener;

    public NpcInventory() {
        super(SLOT_COUNT);
    }

    public static boolean isBagSlot(int slot) {
        return slot >= BAG_START && slot < SLOT_COUNT;
    }

    public void setChangeListener(Consumer<NpcInventory> listener) {
        this.changeListener = listener;
    }

    /**
     * 槽位准入：决定 shift 快速移动时物品会落到哪一格（原版 {@code moveItemStackTo} 按
     * slot 0..29 顺序找第一个 mayPlace 的格子）。
     * <p>
     * 顺序正好是「主手 → 副手 → 四件护甲 → 背包」，所以：
     * 武器进主手、盾进副手、对应护甲进对应装备槽、其余全部落背包。
     */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case MAIN_HAND -> CombatWeapons.isCombatWeapon(stack);
            case OFF_HAND -> stack.canPerformAction(ItemAbilities.SHIELD_BLOCK);
            case HEAD -> fits(stack, EquipmentSlot.HEAD);
            case CHEST -> fits(stack, EquipmentSlot.CHEST);
            case LEGS -> fits(stack, EquipmentSlot.LEGS);
            case FEET -> fits(stack, EquipmentSlot.FEET);
            default -> isBagSlot(slot);
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
        tag.putInt(KEY_LAYOUT, LAYOUT_VERSION);
        return tag.toString();
    }

    public void readFromSnbt(HolderLookup.Provider registries, String snbt) {
        clearSilently();
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            migrateLayout(tag);
            deserializeNBT(registries, tag);
        } catch (Exception invalid) {
            clearSilently();
        }
    }

    /**
     * 老存档槽位重映射：旧布局是「背包 0-23、主手 24、副手 25、头 26、胸 27、腿 28、脚 29」。
     * 没有布局标记的库存按旧布局整体挪到新位置，避免把头盔塞进背包格。
     */
    private static void migrateLayout(CompoundTag tag) {
        if (tag.getInt(KEY_LAYOUT) >= LAYOUT_VERSION) {
            return;
        }
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            entry.putInt("Slot", remapLegacySlot(entry.getInt("Slot")));
        }
        tag.putInt(KEY_LAYOUT, LAYOUT_VERSION);
    }

    private static int remapLegacySlot(int old) {
        return switch (old) {
            case 24 -> MAIN_HAND;
            case 25 -> OFF_HAND;
            case 26 -> HEAD;
            case 27 -> CHEST;
            case 28 -> LEGS;
            case 29 -> FEET;
            default -> old >= 0 && old < BAG_SLOTS ? BAG_START + old : old;
        };
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
