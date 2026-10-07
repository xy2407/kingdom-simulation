package com.xy2407.kingdom.server;

import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.faction.FactionService;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;
import com.xy2407.kingdom.server.faction.TerritoryVitalsStore;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.map.ExploredMapStore;
import com.xy2407.kingdom.server.map.WorldMapServer;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.ranch.RanchWorkService;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端生命周期事件，驱动持久层开关与定时落盘
 */
public final class ServerLifecycle {
    private ServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        NpcManager.INSTANCE.reset();
        BuildingRegistry.INSTANCE.reset();
        FarmPlotManager.INSTANCE.reset();
        RanchPenManager.INSTANCE.reset();
        RanchWorkService.reset();
        /* 阵营与领地：跟着世界一起清，避免换存档时读到上一个世界的国家 */
        NationRegistry.INSTANCE.reset();
        TerritoryRegistry.INSTANCE.reset();
        PlayerProfileStore.INSTANCE.reset();
        /* 民心内存缓存与"昨日人口"基线也随存档一起清掉，避免拿着上局的数算减员 */
        TerritoryVitalsStore.INSTANCE.reset();
        TerritoryVitalsService.reset();
        /* 建筑仓库的会话缓存与"上次写库的 SNBT"同理：换存档必须清，
           否则会把上一个世界某个建筑的库存当成这个世界的（建筑 UUID 不会重复，但缓存会串） */
        BuildingStorageStore.INSTANCE.reset();
        /* 地图采样缓存同理：维度名一样但地形不同，留着会画出上一个世界的图 */
        WorldMapServer.reset();
        /* 已探索地图的待采队列里装的是上一个世界的区块坐标，同样要清 */
        ExploredMapStore.reset();
        SnapshotRepository snapshotRepository = SnapshotRepository.get(event.getServer());
        if (snapshotRepository != null) {
            snapshotRepository.onServerStarting();
        }
        KingdomPersistence instance = KingdomPersistence.get(event.getServer());
        if (instance != null) {
            instance.onServerStarting();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        KingdomPersistence instance = KingdomPersistence.current();
        if (instance != null) {
            instance.onServerTick();
        }
        /* 三张表各自懒加载：数据库就绪后读一次，之后这些调用只是一次布尔判断 */
        NationRegistry.INSTANCE.tick();
        TerritoryRegistry.INSTANCE.tick();
        PlayerProfileStore.INSTANCE.tick();
        TerritoryVitalsStore.INSTANCE.tick();
        /* 建筑仓库：每 60 tick 把改动过的容器写回数据库。
           内部自己数节拍，没动过的建筑零开销（逐条比对 SNBT 才发现差异）。 */
        BuildingStorageStore.INSTANCE.tick(event.getServer());
        /* 档案读完后再驱动首登：未 onboarded 的玩家会收到一次择旗状态包 */
        FactionService.tick(event.getServer());
        /* 已探索地图：把区块加载时入队的那些块按预算分帧采进存档。
           每个维度各有一份队列，所以这里遍历全部维度；队列空时几乎零开销。 */
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ExploredMapStore.tick(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NpcManager.INSTANCE.flushSync();
        /* 建筑仓库：把还没到 60 tick 节拍的那点改动补写一次。
           必须赶在 KingdomPersistence 关掉之前——关掉之后 submit 只会被丢弃。 */
        BuildingStorageStore.INSTANCE.flushAll(event.getServer());
        KingdomPersistence instance = KingdomPersistence.current();
        if (instance != null) {
            instance.onServerStopping();
        }
        KingdomPersistence.clearServerCache(event.getServer());
        KingdomPersistence.forgetServer(event.getServer());
        SnapshotRepository.clearServerCache(event.getServer());
        SnapshotRepository.forgetServer(event.getServer());
    }
}