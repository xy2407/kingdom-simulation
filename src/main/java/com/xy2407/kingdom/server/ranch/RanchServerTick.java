package com.xy2407.kingdom.server.ranch;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 养殖服务端定时任务：驱动养殖循环
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RanchServerTick {

    private RanchServerTick() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        RanchWorkService.tick(event.getServer());
    }
}
