package com.xy2407.kingdom.server.ranch;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.ranch.RanchAnimals;

import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 养殖服务端定时任务：驱动养殖循环，并把基座死亡折算成数量减一
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RanchServerTick {

    private RanchServerTick() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        RanchWorkService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && RanchAnimals.isBase(mob)) {
            RanchAnimals.handleBaseDeath(mob);
        }
    }
}
