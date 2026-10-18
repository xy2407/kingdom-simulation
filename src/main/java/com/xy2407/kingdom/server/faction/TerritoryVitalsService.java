package com.xy2407.kingdom.server.faction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.TerritoryVitals;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 领地民心三值的规则引擎：每日结算凝聚力（战斗损失、开战惩罚、投降）与忠诚幸福导致的人口流失。
 */
public final class TerritoryVitalsService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int COMFORT_THRESHOLD = 60;
    private static final int CRITICAL_THRESHOLD = 30;

    private static final Random RANDOM = new Random();

    private static final Map<String, Integer> LAST_POPULATION = new ConcurrentHashMap<>();

    private TerritoryVitalsService() {
    }

    public static void reset() {
        LAST_POPULATION.clear();
    }

    private record Census(int population, List<NpcData> homelessOrJobless) {
    }


    public static void onNewDay(ServerLevel level, int day) {
        if (level == null || !TerritoryRegistry.INSTANCE.isLoaded()) {
            return;
        }
        List<Territory> territories = TerritoryRegistry.INSTANCE.all();
        for (Territory territory : territories) {
            TerritoryVitals vitals = TerritoryVitalsStore.INSTANCE.of(territory.id());
            if (vitals == null) {
                continue;
            }
            Census census = census(territory);

            settleUnity(level, territory, vitals, census, day);
            settleUnrest(level, territory, vitals, census, day);
            checkSurrender(territory, vitals);

            LAST_POPULATION.put(territory.id(), census.population());
            TerritoryVitalsStore.INSTANCE.persist(vitals);
        }
    }


    private static void settleUnity(ServerLevel level, Territory territory, TerritoryVitals vitals,
                                    Census census, int day) {
        if (vitals.atWar()) {
            int loss = vitals.unityLossFromCasualties(census.population());
            if (loss > 0) {
                LOGGER.info("[民心] 领地 {} 战时减员：开战人口 {}、现 {}，凝聚力 −{} → {}",
                        territory.name(), vitals.warSelfPopulation(), census.population(),
                        loss, vitals.unity());
            }
        }

        Integer previous = LAST_POPULATION.get(territory.id());
        if (previous != null && census.population() < previous) {
            boolean increase = RANDOM.nextBoolean();
            int delta = vitals.randomCasualtySwing(increase);
            LOGGER.info("[民心] 领地 {} 本日减员 {} 人，凝聚力随机 {} → {}",
                    territory.name(), previous - census.population(),
                    (delta >= 0 ? "+" : "") + delta, vitals.unity());
        }
    }

    private static void checkSurrender(Territory territory, TerritoryVitals vitals) {
        if (!vitals.shouldSurrender()) {
            return;
        }
        String enemyId = vitals.warEnemyId();
        Territory enemy = TerritoryRegistry.INSTANCE.byId(enemyId);
        String enemyName = enemy == null ? enemyId : enemy.name();
        LOGGER.warn("[民心] 领地 {} 凝聚力已跌至 {}（低于 {}%），向 {} 投降",
                territory.name(), vitals.unity(), TerritoryVitals.SURRENDER_THRESHOLD, enemyName);

        vitals.endWar();
    }


    private static void settleUnrest(ServerLevel level, Territory territory, TerritoryVitals vitals,
                                     Census census, int day) {
        int loyalty = vitals.loyalty();
        int happiness = vitals.happiness();
        int lowest = Math.min(loyalty, happiness);

        int interval;
        int chancePercent;
        if (loyalty < CRITICAL_THRESHOLD && happiness < CRITICAL_THRESHOLD) {
            interval = 1;
            chancePercent = 60;
        } else if (lowest < CRITICAL_THRESHOLD) {
            interval = 2;
            chancePercent = 50;
        } else if (lowest < COMFORT_THRESHOLD) {
            interval = 4;
            chancePercent = 50;
        } else {
            return;
        }

        if (interval <= 0 || day % interval != 0) {
            return;
        }
        if (RANDOM.nextInt(100) >= chancePercent) {
            return;
        }

        List<NpcData> pool = new ArrayList<>(census.homelessOrJobless());
        if (pool.isEmpty()) {
            return;
        }
        Collections.shuffle(pool, RANDOM);
        int leaving = Math.min(1 + RANDOM.nextInt(2), pool.size());
        for (int index = 0; index < leaving; index++) {
            evict(level, pool.get(index));
        }
        LOGGER.info("[民心] 领地 {} 忠诚 {}、幸福 {}（间隔 {} 天 / {}%），离开 {} 名无住宅或无工作的市民",
                territory.name(), loyalty, happiness, interval, chancePercent, leaving);
    }

    private static void evict(ServerLevel fallback, NpcData npc) {
        if (npc == null) {
            return;
        }
        UUID entityUuid = npc.getEntityUuid();
        if (entityUuid != null) {
            try {
                ServerLevel target = dimensionLevel(fallback, npc);
                Entity found = target == null ? null : target.getEntity(entityUuid);
                if (found instanceof NpcEntity entity) {
                    entity.setCitizenId(null);
                    entity.discard();
                }
            } catch (Throwable ignored) {
            }
        }
        NpcManager.INSTANCE.removeNpc(npc.getId());
    }

    private static ServerLevel dimensionLevel(ServerLevel fallback, NpcData npc) {
        ResourceLocation id = ResourceLocation.tryParse(npc.getDimension());
        if (id == null || fallback == null || fallback.getServer() == null) {
            return fallback;
        }
        ServerLevel level = fallback.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        return level == null ? fallback : level;
    }


    public static Census census(Territory territory) {
        List<NpcData> homelessOrJobless = new ArrayList<>();
        int population = 0;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }
            if (!territory.dimension().equals(npc.getDimension())) {
                continue;
            }
            if (!territory.containsBlock(npc.getX(), npc.getZ())) {
                continue;
            }
            population++;
            if (npc.getHomeId() == null || npc.getWorkplaceId() == null) {
                homelessOrJobless.add(npc);
            }
        }
        return new Census(population, homelessOrJobless);
    }

    public static int populationOf(Territory territory) {
        return census(territory).population();
    }


    public static void beginWar(Territory self, Territory enemy, int day, boolean selfAggressor) {
        if (self == null || enemy == null) {
            return;
        }
        TerritoryVitals vitals = TerritoryVitalsStore.INSTANCE.of(self.id());
        if (vitals == null || vitals.atWar()) {
            return;
        }
        vitals.beginWar(enemy.id(), day, populationOf(self), populationOf(enemy), selfAggressor);
        TerritoryVitalsStore.INSTANCE.persist(vitals);
        LOGGER.info("[民心] {} 对 {} 开战（{}），凝聚力 {}",
                self.name(), enemy.name(), selfAggressor ? "主动" : "被动", vitals.unity());
    }

    public static void endWar(Territory self) {
        if (self == null) {
            return;
        }
        TerritoryVitals vitals = TerritoryVitalsStore.INSTANCE.of(self.id());
        if (vitals == null || !vitals.atWar()) {
            return;
        }
        vitals.endWar();
        TerritoryVitalsStore.INSTANCE.persist(vitals);
        LOGGER.info("[民心] {} 结束战争", self.name());
    }

    public static boolean atWar(Territory territory) {
        TerritoryVitals vitals = territory == null ? null : TerritoryVitalsStore.INSTANCE.peek(territory.id());
        return vitals != null && vitals.atWar();
    }

    public static BlockPos centerOf(Territory territory) {
        return territory == null ? null
                : new BlockPos(territory.centerX(), territory.centerY(), territory.centerZ());
    }
}
