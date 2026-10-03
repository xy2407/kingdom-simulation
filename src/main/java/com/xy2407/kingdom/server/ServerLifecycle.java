package com.xy2407.kingdom.server;

import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.farm.FarmPlotManager;

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