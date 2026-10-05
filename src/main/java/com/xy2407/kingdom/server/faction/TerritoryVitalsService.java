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
 * 领地民心三值的规则引擎：每天结算一次。
 *
 * <p>挂点见 {@code NpcServerTick}：{@code DailyProcessor.registerOnNewDay(...)}，
 * 那是项目现成的日结钩子（含跨天欠账补齐），不必自己数 tick。</p>
 *
 * <h2>凝聚力</h2>
 * <ul>
 *   <li><b>战争减员</b>：以<b>开战瞬间</b>的人口当分母，按"净减员占开战人口的百分比"
 *       扣同等百分比的凝聚力。人口<b>增加不参与计算</b>（分母锁死、且只取正的减员）。</li>
 *   <li><b>主动开战</b>：开战瞬间主动方额外 −{@link TerritoryVitals#AGGRESSOR_PENALTY}。</li>
 *   <li><b>死亡或失踪</b>：本日该领地人口有减少时，凝聚力随机 ±
 *       {@link TerritoryVitals#CASUALTY_SWING}。</li>
 *   <li><b>投降</b>：凝聚力 &lt; {@link TerritoryVitals#SURRENDER_THRESHOLD} 且处于战争状态
 *       → 直接向敌方投降。领地同样适用（"包括玩家的领地"）。</li>
 * </ul>
 *
 * <h2>忠诚度 / 幸福度</h2>
 * <p>两项都 ≥ 60 → 无事发生；否则按最低的那一项分三档，每隔若干游戏天掷一次骰子，
 * 命中就赶走 1~2 个"没有住宅或没有工作"的市民：</p>
 * <table border="1">
 *   <tr><th>条件</th><th>间隔</th><th>概率</th></tr>
 *   <tr><td>两项都 &lt; 30</td><td>每 1 天</td><td>60%</td></tr>
 *   <tr><td>任一项 &lt; 30</td><td>每 2 天</td><td>50%</td></tr>
 *   <tr><td>任一项 &lt; 60（且都不 &lt; 30）</td><td>每 4 天</td><td>50%</td></tr>
 * </table>
 *
 * <p><b>目前没有"抬高"这三个值的渠道</b>（建筑、政策、事件等一概未接），
 * 所以它们只会被上面这些规则往下压——这正是"渠道留空"的现状。</p>
 */
public final class TerritoryVitalsService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 忠诚/幸福都到这条线以上就不触发人口流失。 */
    private static final int COMFORT_THRESHOLD = 60;
    /** 低于这条线进入更频繁的一档。 */
    private static final int CRITICAL_THRESHOLD = 30;

    private static final Random RANDOM = new Random();

    /**
     * 上一日各领地的人口，用来判断"今日有没有人减少"。
     *
     * <p>只放内存、不落库：重启后第一天的对比基准丢失，那一天不会触发
     * "死亡或失踪"的随机增减——可以接受，不值得为它加一张表。</p>
     */
    private static final Map<String, Integer> LAST_POPULATION = new ConcurrentHashMap<>();

    private TerritoryVitalsService() {
    }

    public static void reset() {
        LAST_POPULATION.clear();
    }

    /** 一次扫描的产物：既算人口，也顺手挑出"无住宅或无工作"的人。 */
    private record Census(int population, List<NpcData> homelessOrJobless) {
    }

    // ------------------------------------------------------------------ 日结

    /**
     * 每日结算。由 {@code DailyProcessor} 的日结回调驱动，跑在主线程上，
     * 所以要动实体是安全的。
     */
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

    // ------------------------------------------------------------------ 凝聚力

    /** 战争减员 + 死亡/失踪的随机涨落。 */
    private static void settleUnity(ServerLevel level, Territory territory, TerritoryVitals vitals,
                                    Census census, int day) {
        if (vitals.atWar()) {
            /* 分母是开战那一刻的人口（beginWar 记下的），当前人口只用来算净减员。
               减员为负（人口反而涨了）时 unityLossFromCasualties 返回 0，
               即"增加人口不进行计算"。 */
            int loss = vitals.unityLossFromCasualties(census.population());
            if (loss > 0) {
                LOGGER.info("[民心] 领地 {} 战时减员：开战人口 {}、现 {}，凝聚力 −{} → {}",
                        territory.name(), vitals.warSelfPopulation(), census.population(),
                        loss, vitals.unity());
            }
        }

        Integer previous = LAST_POPULATION.get(territory.id());
        if (previous != null && census.population() < previous) {
            /* 本日该领地确实少了人（死亡或失踪）。按规则随机 ±20%。
               注意这一条与上面的"按比例扣"是两条独立规则，会叠加——
               原文就是这么写的，故照实现。 */
            boolean increase = RANDOM.nextBoolean();
            int delta = vitals.randomCasualtySwing(increase);
            LOGGER.info("[民心] 领地 {} 本日减员 {} 人，凝聚力随机 {} → {}",
                    territory.name(), previous - census.population(),
                    (delta >= 0 ? "+" : "") + delta, vitals.unity());
        }
    }

    /** 跌破投降线且正在交战 → 投降。 */
    private static void checkSurrender(Territory territory, TerritoryVitals vitals) {
        if (!vitals.shouldSurrender()) {
            return;
        }
        String enemyId = vitals.warEnemyId();
        Territory enemy = TerritoryRegistry.INSTANCE.byId(enemyId);
        String enemyName = enemy == null ? enemyId : enemy.name();
        LOGGER.warn("[民心] 领地 {} 凝聚力已跌至 {}（低于 {}%），向 {} 投降",
                territory.name(), vitals.unity(), TerritoryVitals.SURRENDER_THRESHOLD, enemyName);

        /* TODO 战争系统接上后在这里完成"领地易主"：
           把 territory.owner / nationId 改成敌方的。当前 Territory 是不可变 record
           且 TerritoryRegistry 没有更新入口，所以先只结束战争状态、留下这条日志。 */
        vitals.endWar();
    }

    // ------------------------------------------------------------------ 忠诚 / 幸福

    /** 按忠诚/幸福的最低档位，掷骰决定是否赶人。 */
    private static void settleUnrest(ServerLevel level, Territory territory, TerritoryVitals vitals,
                                     Census census, int day) {
        int loyalty = vitals.loyalty();
        int happiness = vitals.happiness();
        int lowest = Math.min(loyalty, happiness);

        int interval;
        int chancePercent;
        if (loyalty < CRITICAL_THRESHOLD && happiness < CRITICAL_THRESHOLD) {
            interval = 1;   // 双低：每游戏天
            chancePercent = 60;
        } else if (lowest < CRITICAL_THRESHOLD) {
            interval = 2;   // 任一低：每 2 游戏天
            chancePercent = 50;
        } else if (lowest < COMFORT_THRESHOLD) {
            interval = 4;   // 任一项低于 60：每 4 游戏天
            chancePercent = 50;
        } else {
            return;         // 两项都在 60 以上，无事发生
        }

        if (interval <= 0 || day % interval != 0) {
            return;
        }
        if (RANDOM.nextInt(100) >= chancePercent) {
            return;
        }

        List<NpcData> pool = new ArrayList<>(census.homelessOrJobless());
        if (pool.isEmpty()) {
            return;         // 没有符合条件的市民，这次空过
        }
        Collections.shuffle(pool, RANDOM);
        int leaving = Math.min(1 + RANDOM.nextInt(2), pool.size());   // 1~2 个
        for (int index = 0; index < leaving; index++) {
            evict(level, pool.get(index));
        }
        LOGGER.info("[民心] 领地 {} 忠诚 {}、幸福 {}（间隔 {} 天 / {}%），离开 {} 名无住宅或无工作的市民",
                territory.name(), loyalty, happiness, interval, chancePercent, leaving);
    }

    /** 让一名市民离开：数据行删掉，世界里的实体也一并清掉，免得留下无主实体。 */
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
                // 实体找不到/维度没加载都无所谓：数据行照样删掉
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

    // ------------------------------------------------------------------ 普查

    /**
     * 数一块领地里的市民，并挑出"没有住宅或没有工作"的那些。
     *
     * <p>市民身上没有领地字段，所以只能按坐标筛：同维度 + 落在领地范围内。
     * 市民的位置由 {@code NpcManager.tick} 持续同步，所以这个口径是可靠的。
     * 一次遍历同时得到人口和名单，避免扫两遍。</p>
     */
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
            /* "没有住宅或没有工作" = homeId / workplaceId 为 null。
               这两个字段存的是建筑 id，null 表示还没分配。 */
            if (npc.getHomeId() == null || npc.getWorkplaceId() == null) {
                homelessOrJobless.add(npc);
            }
        }
        return new Census(population, homelessOrJobless);
    }

    /** 只要人口数（给 UI / 凝聚力用）。 */
    public static int populationOf(Territory territory) {
        return census(territory).population();
    }

    // ------------------------------------------------------------------ 战争入口（骨架）

    /**
     * 开战：记录双方<b>此刻</b>的人口，并给主动方扣 10% 凝聚力。
     *
     * <p><b>目前没有任何调用方</b>——项目里还没有战争系统。这一对入口
     * （{@link #beginWar}/{@link #endWar}）连同上面整套凝聚力规则先备好，
     * 等战争系统落地后直接调用即可，规则侧不用再动。</p>
     *
     * @param self           本方领地
     * @param enemy          敌方领地
     * @param day            开战时的世界天数
     * @param selfAggressor  本方是否为主动开战方
     */
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

    /** 停战。 */
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

    /** 该领地当前是否处于战争（给 UI / 其它系统查）。 */
    public static boolean atWar(Territory territory) {
        TerritoryVitals vitals = territory == null ? null : TerritoryVitalsStore.INSTANCE.peek(territory.id());
        return vitals != null && vitals.atWar();
    }

    /** 领地中心方块的 BlockPos（给需要定位的调用方用）。 */
    public static BlockPos centerOf(Territory territory) {
        return territory == null ? null : new BlockPos(territory.centerX(), 0, territory.centerZ());
    }
}
