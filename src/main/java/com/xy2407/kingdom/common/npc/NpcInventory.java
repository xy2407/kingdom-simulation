package com.xy2407.kingdom.common.npc;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.Consumer;

/**
 * 市民随身行装：固定 24 格。
 *
 * <p>继承 {@link ItemStackHandler} 是为了直接作为 {@code Capabilities.ItemHandler.ENTITY}
 * 对外暴露给 AUI 的容器菜单——槽位点击、拖拽、shift-click 全部由 AUI 的
 * {@code ApricityContainerMenu} 处理，这里只负责"存哪儿"和"什么时候落库"。</p>
 *
 * <p>内容变化通过 {@link #setChangeListener} 回调给实体，由实体写回 {@link NpcData}
 * （数据库是市民状态的唯一真源：实体只是 shell，重建时不会带上 NBT）。</p>
 */
public class NpcInventory extends ItemStackHandler {

    /** 每个市民的行装格数。 */
    public static final int SLOT_COUNT = 24;

    private Consumer<NpcInventory> changeListener;

    public NpcInventory() {
        super(SLOT_COUNT);
    }

    /** 注册内容变化回调（由实体在字段初始化时挂上）。 */
    public void setChangeListener(Consumer<NpcInventory> listener) {
        this.changeListener = listener;
    }

    @Override
    protected void onContentsChanged(int slot) {
        if (changeListener != null) {
            changeListener.accept(this);
        }
    }

    /** 清空全部格子，且不触发落库回调（载入前用）。 */
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

    /** 序列化成 SNBT 字符串，便于直接塞进数据库的 TEXT 列；空库存返回空串。 */
    public String writeToSnbt(HolderLookup.Provider registries) {
        CompoundTag tag = serializeNBT(registries);
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        return tag.toString();
    }

    /** 从 SNBT 读回；传空串或解析失败时清空，绝不抛异常打断实体加载。 */
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

    /** 是否 24 格全空（用于展示与统计）。 */
    public boolean isEmpty() {
        for (int slot = 0; slot < getSlots(); slot++) {
            if (!getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
