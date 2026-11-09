package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.common.network.builder.BuilderOpenPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑工方块：右键打开面板，潜行右键开六行材料箱，被拆时把箱内材料散落出来
 */
public class BuilderBlock extends Block implements EntityBlock {

    public BuilderBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuilderBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()
                    && level.getBlockEntity(pos) instanceof BuilderBlockEntity container) {
                serverPlayer.openMenu(new SimpleMenuProvider(
                        (id, inventory, viewer) -> ChestMenu.sixRows(id, inventory, container),
                        Component.literal("建筑工材料")));
            } else {
                PacketDistributor.sendToPlayer(serverPlayer, new BuilderOpenPacket());
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BuilderBlockEntity container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D,
                            pos.getZ() + 0.5D, stack);
                }
            }
            container.clearContent();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
