package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.farm.FarmPlotManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端施工调度入口，每 tick 推进建筑任务
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuildingServerTick {

    private BuildingServerTick() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BuildingConstructionService.INSTANCE.tick(event.getServer());
        BuildingRegistry.INSTANCE.tick(event.getServer());
        FarmPlotManager.INSTANCE.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BuildingConstructionService.INSTANCE.syncTo(player);
        }
    }
}
