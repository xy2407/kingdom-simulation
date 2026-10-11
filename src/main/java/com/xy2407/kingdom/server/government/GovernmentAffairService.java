package com.xy2407.kingdom.server.government;

import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.TerritoryTier;
import com.xy2407.kingdom.common.government.GovernmentAffair;
import com.xy2407.kingdom.common.government.GovernmentAffairType;
import com.xy2407.kingdom.server.cityhall.CityHallDataCollector;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 政务生成与过期：每 4000 tick 各领地各掷一次半概率新增一条
 */
public final class GovernmentAffairService {

    private static final int DRAW_INTERVAL_TICKS = 4000;

    private static final int DRAW_BOUND = 2;

    private static int drawCounter;

    private GovernmentAffairService() {
    }

    public static void reset() {
        drawCounter = 0;
    }

    public static void tick(MinecraftServer server) {
        if (++drawCounter < DRAW_INTERVAL_TICKS) {
            return;
        }
        drawCounter = 0;
        draw(server);
    }

    public static void onNewDay(int day) {
        GovernmentAffairStore.INSTANCE.expireUpTo(day);
    }

    public static void refreshCityHall(ServerPlayer player, int x, int z) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player,
                CityHallDataCollector.collect(level, player, new BlockPos(x, 0, z)));
    }

    public static void resolveAffair(ServerPlayer player, int x, int z, String affairId) {
        ServerLevel level = player.serverLevel();
        Territory territory = TerritoryRegistry.INSTANCE.byChunk(
                level.dimension().location().toString(), x >> 4, z >> 4);
        if (territory == null) {
            return;
        }
        if (!player.getUUID().equals(territory.owner())) {
            player.displayClientMessage(Component.literal("只有该领地的领主才能办理政务"), true);
            return;
        }
        if (GovernmentAffairStore.INSTANCE.resolve(territory.id(), affairId)) {
            PacketDistributor.sendToPlayer(player,
                    CityHallDataCollector.collect(level, player, new BlockPos(x, 0, z)));
        }
    }

    private static void draw(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        int today = DateSystem.totalDay(overworld);
        RandomSource random = overworld.getRandom();
        for (Territory territory : TerritoryRegistry.INSTANCE.all()) {
            if (random.nextInt(DRAW_BOUND) != 0) {
                continue;
            }
            int population = TerritoryVitalsService.populationOf(territory);
            List<GovernmentAffairType> pool = GovernmentAffairType.unlockedFor(TerritoryTier.of(population));
            if (pool.isEmpty()) {
                continue;
            }
            GovernmentAffairType type = pool.get(random.nextInt(pool.size()));
            GovernmentAffairStore.INSTANCE.add(new GovernmentAffair(
                    UUID.randomUUID().toString(),
                    territory.id(),
                    type.name(),
                    today,
                    today + GovernmentAffair.LIFETIME_DAYS));
        }
    }
}
