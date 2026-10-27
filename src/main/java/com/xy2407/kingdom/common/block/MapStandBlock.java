package com.xy2407.kingdom.common.block;

import com.mojang.serialization.MapCodec;
import com.xy2407.kingdom.server.building.MapStandService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

/** 仓库地图架：右键打开领地管辖地图，仅仓库建筑内可用。 */
public class MapStandBlock extends Block implements EntityBlock {

    public static final MapCodec<MapStandBlock> CODEC = simpleCodec(MapStandBlock::new);
    public static final String PAGE = "buildings/map_stand.html";

    public MapStandBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MapStandBlockEntity(pos, state);
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
        if (MapStandService.warehouseAt(serverLevel, pos) == null) {
            serverPlayer.displayClientMessage(Component.literal("这个地图架要放在仓库建筑里才能用"), true);
            return true;
        }
        MapStandService.open(serverPlayer, pos);
        return true;
    }
}
