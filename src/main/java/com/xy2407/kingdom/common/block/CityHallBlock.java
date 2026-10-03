package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.common.network.cityhall.CityHallOpenPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 市政厅操作台：右键打开城镇管理面板
 */
public class CityHallBlock extends Block {

    public CityHallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new CityHallOpenPacket());
        }
        return InteractionResult.SUCCESS;
    }
}
