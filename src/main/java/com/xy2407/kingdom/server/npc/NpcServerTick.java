package com.xy2407.kingdom.server.npc;

import com.xy2407.kingdom.common.date.DailyProcessor;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 服务端市民调度：加载、定时落盘与每日年龄推进
 */
public final class NpcServerTick {
    private static boolean dailyRegistered;

    /**
     * 当前服务器。日结回调只给 day、不给 level，而民心结算要动世界里的实体，
     * 所以这里留一份引用供回调取 {@code overworld()}（换存档时 onServerStarting 会刷新它）。
     */
    private static MinecraftServer server;

    private NpcServerTick() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        if (!dailyRegistered) {
            dailyRegistered = true;
            DailyProcessor.registerOnNewDay(day -> NpcManager.INSTANCE.onNewDay(day));
            /* 领地民心：忠诚/幸福导致的人口流失、凝聚力的战争减员与投降判定，
               都在日结这里结算一次（DailyProcessor 自带跨天欠账补齐）。 */
            DailyProcessor.registerOnNewDay(day -> {
                MinecraftServer current = server;
                TerritoryVitalsService.onNewDay(current == null ? null : current.overworld(), day);
            });
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        SnapshotRepository repository = SnapshotRepository.get(event.getServer());
        // 没有自定义 A* 需求时不推进快照，省掉常驻的每 tick 一次区块重建。
        if (repository != null && overworld != null && repository.hasDemand(overworld)) {
            repository.tick(overworld);
        }
        NpcManager.INSTANCE.tick(overworld);
    }
}
