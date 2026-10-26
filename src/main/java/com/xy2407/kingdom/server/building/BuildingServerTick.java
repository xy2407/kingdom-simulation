package com.xy2407.kingdom.server.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.npc.NpcIndustryService;
import com.xy2407.kingdom.common.npc.NpcRestaurantService;
import com.xy2407.kingdom.common.npc.NpcShopService;
import com.xy2407.kingdom.server.farm.FarmPlotManager;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import com.xy2407.kingdom.server.restaurant.RestaurantOrderStore;
import com.xy2407.kingdom.server.restaurant.RestaurantSeatStore;
import com.xy2407.kingdom.server.shop.ShopStore;
import com.xy2407.kingdom.server.task.RegionTaskService;

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

    private static final int INDUSTRY_ASSIGN_INTERVAL_TICKS = 100;
    private static int industryAssignCounter;

    private BuildingServerTick() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BuildingConstructionService.INSTANCE.tick(event.getServer());
        BuildingRegistry.INSTANCE.tick(event.getServer());
        RegionTaskService.INSTANCE.tick(event.getServer());
        FarmPlotManager.INSTANCE.tick(event.getServer());
        RanchPenManager.INSTANCE.tick(event.getServer());
        RestaurantOrderStore.INSTANCE.tick(event.getServer());
        RestaurantSeatStore.INSTANCE.tick(event.getServer());
        ShopStore.INSTANCE.tick(event.getServer());
        if (++industryAssignCounter % INDUSTRY_ASSIGN_INTERVAL_TICKS == 0) {
            NpcIndustryService.assignWorkers(event.getServer());
            NpcRestaurantService.assignWorkers(event.getServer());
            NpcShopService.assignWorkers(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BuildingConstructionService.INSTANCE.syncTo(player);
        }
    }
}
