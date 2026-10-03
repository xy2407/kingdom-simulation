package com.xy2407.kingdom.server.npc;

import com.xy2407.kingdom.common.date.DailyProcessor;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;

/**
 * 服务端市民调度：加载、定时落盘与每日年龄推进
 */
public final class NpcServerTick {
    private static boolean dailyRegistered;

    private NpcServerTick() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!dailyRegistered) {
            dailyRegistered = true;
            DailyProcessor.registerOnNewDay(day -> NpcManager.INSTANCE.onNewDay(day));
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
