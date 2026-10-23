package com.xy2407.kingdom.server.npc;

import com.xy2407.kingdom.common.date.DailyProcessor;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcMiningService;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.server.mining.MiningPlotManager;
import com.xy2407.kingdom.server.mining.MiningScanService;
import com.xy2407.kingdom.server.path.UnitSnapshotStore;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;
import com.xy2407.kingdom.server.government.GovernmentAffairService;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 服务端市民调度：加载、定时落盘与每日年龄推进。
 */
public final class NpcServerTick {
    private static boolean dailyRegistered;

    private static MinecraftServer server;

    private NpcServerTick() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        CommuteScheduler.reset();
        if (!dailyRegistered) {
            dailyRegistered = true;
            DailyProcessor.registerOnNewDay(day -> NpcManager.INSTANCE.onNewDay(day));
            DailyProcessor.registerOnNewDay(day -> {
                MinecraftServer current = server;
                TerritoryVitalsService.onNewDay(current == null ? null : current.overworld(), day);
            });
            DailyProcessor.registerOnNewDay(GovernmentAffairService::onNewDay);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        SnapshotRepository repository = SnapshotRepository.get(event.getServer());
        if (repository != null) {
            repository.resetTickSearches();
        }
        UnitSnapshotStore.INSTANCE.tick(event.getServer());
        MiningPlotManager.INSTANCE.tick(event.getServer());
        com.xy2407.kingdom.server.area.PlotAreaManager.INSTANCE.tick(event.getServer());
        NpcMiningService.assign(event.getServer());
        NpcManager.INSTANCE.tick(overworld);
        CommuteScheduler.tick(event.getServer());
        MiningScanService.tick(event.getServer());
        com.xy2407.kingdom.server.lumber.LumberSurveyService.INSTANCE.tick(event.getServer());
        com.xy2407.kingdom.server.tourism.TourismService.INSTANCE.tick(event.getServer());
    }
}
