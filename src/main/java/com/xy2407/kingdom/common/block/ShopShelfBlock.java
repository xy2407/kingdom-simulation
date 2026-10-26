package com.xy2407.kingdom.common.block;

import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.building.BuildingStorageOpenPacket;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 商铺货架方块：右键开货架容器，位于领地内时只有该领地领主能打开
 */
public class ShopShelfBlock extends Block implements EntityBlock {

    public static final String PAGE = "buildings/shelf.html";

    public ShopShelfBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShopShelfBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byBlock(
                serverLevel.dimension().location().toString(), pos.getX(), pos.getZ());
        if (territory != null && territory.owner() != null
                && !territory.owner().equals(serverPlayer.getUUID())) {
            serverPlayer.displayClientMessage(
                    Component.literal("这块货架属于 " + territory.name() + "，只有领主能打开"), true);
            return InteractionResult.SUCCESS;
        }
        PacketDistributor.sendToPlayer(serverPlayer,
                new BuildingStorageOpenPacket("商铺货架", ShopShelfBlockEntity.SLOTS,
                        ShopShelfBlockEntity.SLOTS));
        new PendingMenu(serverPlayer, PAGE).bind(binding -> binding
                .blockEntity(pos, ShopShelfBlockEntity.SLOTS)
                .player());
        return InteractionResult.SUCCESS;
    }
}
