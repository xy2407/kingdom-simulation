package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.ModMain;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 服务端兜底清理：掉线 / 死亡重生 / 停服时把替身和选中集合收干净。
 *
 * <p>正常退出走客户端的回收包（{@code RtsFakePlayerClearPacket}），这里只处理"没走到那一步"的情况，
 * 避免替身残留在世界里。对应 nsuk 的 {@code RtsServerTick} 里那几段清理，砍掉了全部队伍逻辑。</p>
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

    /** 死亡与重生都会触发 Clone：两头都清，避免旧实体残留。 */
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
