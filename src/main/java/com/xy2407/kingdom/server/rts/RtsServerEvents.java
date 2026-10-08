package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.ModMain;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 服务端兜底清理：掉线、死亡重生、停服时把替身与选中集合收干净。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class RtsServerEvents {

    private RtsServerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RtsFakePlayerService.clearFor(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RtsFakePlayerService.clearFor(player);
        }
        if (event.getOriginal() instanceof ServerPlayer original) {
            RtsFakePlayerService.clearFor(original);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RtsFakePlayerService.clearAll(event.getServer());
    }
}
