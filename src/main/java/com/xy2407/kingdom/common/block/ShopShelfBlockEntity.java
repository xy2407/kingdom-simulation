package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 商品展示架方块实体：三层台阶共 9 个陈列位，每格自带价格（默认 10 金币）。
 */
public class ShopShelfBlockEntity extends BlockEntity implements Container {

    public static final int ROWS = 3;
    public static final int COLS = 3;
    public static final int SLOTS = ROWS * COLS;
    public static final int DEFAULT_PRICE = 10;

    private static final String KEY_ITEMS = "Items";
    private static final String KEY_PRICES = "Prices";

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final int[] prices = new int[SLOTS];

    public ShopShelfBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP_SHELF.get(), pos, state);
        java.util.Arrays.fill(prices, DEFAULT_PRICE);
    }

    public int priceOf(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return DEFAULT_PRICE;
        }
        int price = prices[slot];
        return price <= 0 ? DEFAULT_PRICE : price;
    }

    public void setPrice(int slot, int price) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        prices[slot] = Math.max(0, price);
        setChanged();
        sync();
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < 0 || slot >= SLOTS ? ItemStack.EMPTY : items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            setChanged();
            sync();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
        sync();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && !player.isRemoved()
                && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
        sync();
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putIntArray(KEY_PRICES, prices);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        java.util.Arrays.fill(prices, DEFAULT_PRICE);
        int[] saved = tag.getIntArray(KEY_PRICES);
        for (int i = 0; i < Math.min(saved.length, SLOTS); i++) {
            prices[i] = saved[i] <= 0 ? DEFAULT_PRICE : saved[i];
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
