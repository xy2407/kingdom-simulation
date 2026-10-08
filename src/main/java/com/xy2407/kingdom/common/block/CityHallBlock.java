package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.network.cityhall.CityHallOpenPacket;
import com.xy2407.kingdom.server.cityhall.CityHallDataCollector;
import com.xy2407.kingdom.server.faction.FactionService;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
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
 * 市政厅操作台：右键打开城镇管理面板，还没领地且领地表已读完时改引去建城界面。
 */
public class CityHallBlock extends Block {

    public CityHallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            Territory owned = TerritoryRegistry.INSTANCE.byOwner(serverPlayer.getUUID());
            PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(serverPlayer.getUUID());
            boolean hasTerritory = owned != null || (profile != null && profile.hasTerritory());

            if (!hasTerritory && TerritoryRegistry.INSTANCE.isLoaded()) {
                ModMain.LOGGER.info("[市政厅] pos={} 判定无领地 → 建城界面", pos);
                FactionService.promptCityCreate(serverPlayer, pos);
                return InteractionResult.SUCCESS;
            }
            ModMain.LOGGER.info("[市政厅] pos={} 有领地={} → 管理面板", pos, hasTerritory);

            PacketDistributor.sendToPlayer(serverPlayer,
                    new CityHallOpenPacket(pos.getX(), pos.getZ()));
            CityHallDataPacket data;
            try {
                data = CityHallDataCollector.collect(serverLevel, pos, serverPlayer.getUUID());
            } catch (RuntimeException failure) {
                ModMain.LOGGER.warn("[市政厅] 数据收集失败，界面按空数据打开", failure);
                data = CityHallDataPacket.EMPTY;
            }
            PacketDistributor.sendToPlayer(serverPlayer, data);
        }
        return InteractionResult.SUCCESS;
    }
}
