package com.xy2407.kingdom.server.cityhall;

import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.server.faction.MoneyService;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 市政厅金库服务端：校验领主身份后执行存入或提取，并回推最新数据
 */
public final class CityHallTreasuryService {

    private static final long MAX_AMOUNT = 1_000_000_000L;

    private CityHallTreasuryService() {
    }

    public static void apply(ServerPlayer player, int x, int z, boolean deposit, long amount) {
        if (player == null) {
            return;
        }
        if (amount <= 0L || amount > MAX_AMOUNT) {
            player.displayClientMessage(Component.literal("金额无效"), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        Territory territory = TerritoryRegistry.INSTANCE.byBlock(
                level.dimension().location().toString(), x, z);
        if (territory == null || territory.owner() == null
                || !territory.owner().equals(player.getUUID())) {
            player.displayClientMessage(Component.literal("只有这块领地的领主可以调动金库"), true);
            return;
        }
        MoneyService.Result result = deposit
                ? MoneyService.depositToTerritory(player.getUUID(), territory.id(), amount)
                : MoneyService.withdrawFromTerritory(player.getUUID(), territory.id(), amount);
        String message = result.success()
                ? (deposit ? "已存入 " : "已提取 ") + amount + "，金库余额 "
                        + MoneyService.territoryBalance(territory.id())
                : (deposit ? "钱包余额不足" : "金库余额不足");
        player.displayClientMessage(Component.literal(message), true);
        PacketDistributor.sendToPlayer(player,
                CityHallDataCollector.collect(level, player, new BlockPos(x, player.getBlockY(), z)));
    }
}
