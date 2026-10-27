package com.xy2407.kingdom.common.block;

import com.mojang.serialization.MapCodec;
import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 商品展示架：三层台阶九格陈列，右键开 3×3 容器，领地内仅领主可打开。
 */
public class ShopShelfBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<ShopShelfBlock> CODEC = simpleCodec(ShopShelfBlock::new);
    public static final String PAGE = "buildings/shelf.html";

    public ShopShelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShopShelfBlockEntity(pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel && !player.isCreative()
                && level.getBlockEntity(pos) instanceof ShopShelfBlockEntity shelf) {
            for (int slot = 0; slot < ShopShelfBlockEntity.SLOTS; slot++) {
                ItemStack stack = shelf.getItem(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, pos, stack);
                }
            }
            shelf.clearContent();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        return open(state, level, pos, player)
                ? ItemInteractionResult.SUCCESS
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return open(state, level, pos, player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static boolean open(BlockState state, Level level, BlockPos pos, Player player) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byBlock(
                serverLevel.dimension().location().toString(), pos.getX(), pos.getZ());
        if (territory != null && territory.owner() != null
                && !territory.owner().equals(serverPlayer.getUUID())) {
            serverPlayer.displayClientMessage(Component.empty()
                    .append(Component.literal("◆ ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(territory.name()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" 的货架 · 仅领主可开启").withStyle(ChatFormatting.GRAY)),
                    true);
            return true;
        }
        new PendingMenu(serverPlayer, PAGE).bind(binding -> binding
                .blockEntity(pos, ShopShelfBlockEntity.SLOTS)
                .player());
        return true;
    }
}
