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
import com.xy2407.kingdom.server.government.GovernmentAffairService;
import com.xy2407.kingdom.server.government.GovernmentAffairStore;
import com.xy2407.kingdom.server.mapoverlay.MapOverlayCollector;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.ranch.RanchWorkService;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;

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
        RestaurantOrderStore.INSTANCE.reset();
        NationRegistry.INSTANCE.reset();
        TerritoryRegistry.INSTANCE.reset();
        PlayerProfileStore.INSTANCE.reset();
        TerritoryVitalsStore.INSTANCE.reset();
        TerritoryVitalsService.reset();
        BuildingStorageStore.INSTANCE.reset();
        GovernmentAffairStore.INSTANCE.reset();
        GovernmentAffairService.reset();
        MapOverlayCollector.install(event.getServer());
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
        NationRegistry.INSTANCE.tick();
        TerritoryRegistry.INSTANCE.tick();
        PlayerProfileStore.INSTANCE.tick();
        TerritoryVitalsStore.INSTANCE.tick();
        BuildingStorageStore.INSTANCE.tick(event.getServer());
        GovernmentAffairStore.INSTANCE.tick(event.getServer());
        GovernmentAffairService.tick(event.getServer());
        FactionService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NpcManager.INSTANCE.flushSync();
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