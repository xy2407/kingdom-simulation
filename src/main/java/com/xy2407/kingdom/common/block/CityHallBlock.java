package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.common.network.cityhall.CityHallOpenPacket;
import com.xy2407.kingdom.server.cityhall.CityHallDataCollector;
import com.xy2407.kingdom.server.faction.FactionService;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            /* 分流：还没有领地的人，右键市政厅是"建城"；有领地的人，才是"管理"。
               这样新玩家放下市政厅右键一次就能立领地，不必先走别的入口。 */
            if (TerritoryRegistry.INSTANCE.byOwner(serverPlayer.getUUID()) == null) {
                FactionService.promptCityCreate(serverPlayer, pos);
                return InteractionResult.SUCCESS;
            }
            /* 数据包在前、开屏包在后：这样界面打开时数据已经就位，不会先闪一帧占位值。
               客户端两侧都做了兜底，所以就算顺序反了也只是晚一帧刷新。 */
            PacketDistributor.sendToPlayer(serverPlayer,
                    CityHallDataCollector.collect(serverLevel, pos));
            /* 再把方块坐标带过去：界面里的地图要按它为中心采样世界表面 */
            PacketDistributor.sendToPlayer(serverPlayer,
                    new CityHallOpenPacket(pos.getX(), pos.getZ()));
        }
        return InteractionResult.SUCCESS;
    }
}
