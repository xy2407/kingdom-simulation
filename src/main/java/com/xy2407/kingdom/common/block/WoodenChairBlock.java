package com.xy2407.kingdom.common.block;

import com.mojang.serialization.MapCodec;
import com.xy2407.kingdom.common.entity.SeatEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 木椅方块：四向朝向可选，整格标准碰撞，右键生成座位实体让玩家坐下
 */
public class WoodenChairBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<WoodenChairBlock> CODEC = simpleCodec(WoodenChairBlock::new);

    private static final double SEAT_OFFSET = 0.5D;

    public WoodenChairBlock(Properties properties) {
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        if (player.getVehicle() instanceof SeatEntity) {
            return InteractionResult.SUCCESS;
        }
        SeatEntity seat = SeatEntity.create(serverLevel, pos.getX() + 0.5D,
                pos.getY() + SEAT_OFFSET, pos.getZ() + 0.5D);
        if (seat == null) {
            return InteractionResult.SUCCESS;
        }
        serverLevel.addFreshEntity(seat);
        if (!player.startRiding(seat, true)) {
            seat.discard();
            return InteractionResult.SUCCESS;
        }
        player.setForcedPose(Pose.SITTING);
        return InteractionResult.SUCCESS;
    }
}
