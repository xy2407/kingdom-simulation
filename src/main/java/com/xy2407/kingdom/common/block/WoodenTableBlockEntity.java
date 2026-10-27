package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 木桌方块实体：只记桌面上摆着的一件物品，改动即同步给客户端渲染
 */
public class WoodenTableBlockEntity extends BlockEntity {

    private static final String KEY_ITEM = "Display";

    private ItemStack display = ItemStack.EMPTY;

    public WoodenTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WOODEN_TABLE.get(), pos, state);
    }

    public ItemStack display() {
        return display;
    }

    public void setDisplay(ItemStack stack) {
        display = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        setChanged();
        sync();
    }

    public ItemStack takeDisplay() {
        ItemStack taken = display;
        display = ItemStack.EMPTY;
        setChanged();
        sync();
        return taken;
    }

    private void sync() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(this);
        double cx = worldPosition.getX() + 0.5D;
        double cy = worldPosition.getY() + 0.5D;
        double cz = worldPosition.getZ() + 0.5D;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(cx, cy, cz) <= 64.0D * 64.0D) {
                player.connection.send(packet);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!display.isEmpty()) {
            tag.put(KEY_ITEM, display.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        display = tag.contains(KEY_ITEM)
                ? ItemStack.parse(registries, tag.getCompound(KEY_ITEM)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
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
