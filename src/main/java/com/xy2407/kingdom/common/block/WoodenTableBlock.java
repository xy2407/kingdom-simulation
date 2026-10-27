package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 木桌方块：手持物品右键摆到桌面，空手右键取回，破坏时把桌上物品掉出来
 */
public class WoodenTableBlock extends Block implements EntityBlock {

    public WoodenTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WoodenTableBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof WoodenTableBlockEntity table)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.isEmpty()) {
            return take(table, level, player)
                    ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!table.display().isEmpty()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        table.setDisplay(stack);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof WoodenTableBlockEntity table)) {
            return InteractionResult.PASS;
        }
        return take(table, level, player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static boolean take(WoodenTableBlockEntity table, Level level, Player player) {
        if (level.isClientSide || table.display().isEmpty()) {
            return false;
        }
        ItemStack taken = table.takeDisplay();
        if (!player.addItem(taken)) {
            player.drop(taken, false);
        }
        return true;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WoodenTableBlockEntity table
                && !table.display().isEmpty()) {
            Block.popResource(level, pos, table.takeDisplay());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
