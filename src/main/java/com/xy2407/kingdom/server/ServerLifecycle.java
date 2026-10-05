package com.xy2407.kingdom.server;

import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.FactionService;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;
import com.xy2407.kingdom.server.faction.TerritoryVitalsStore;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.map.WorldMapServer;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.ranch.RanchWorkService;

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
        /* 地图采样缓存同理：维度名一样但地形不同，留着会画出上一个世界的图 */
        WorldMapServer.reset();
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
        /* 档案读完后再驱动首登：未 onboarded 的玩家会收到一次择旗状态包 */
        FactionService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NpcManager.INSTANCE.flushSync();
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