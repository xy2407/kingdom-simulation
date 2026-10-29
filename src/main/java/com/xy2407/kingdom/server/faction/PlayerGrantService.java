package com.xy2407.kingdom.server.faction;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 启动资金发放：按 UUID 只发一次，发放记录同时落库防止重启重领
 */
public final class PlayerGrantService {

    private static final long DAY_TICKS = 24000L;

    private PlayerGrantService() {
    }

    public static void grantIfNeeded(ServerPlayer player) {
        if (player == null || !PlayerGrantStore.INSTANCE.isLoaded()) {
            return;
        }
        UUID uuid = player.getUUID();
        if (PlayerGrantStore.INSTANCE.isGranted(uuid)) {
            return;
        }
        int day = (int) (player.level().getDayTime() / DAY_TICKS);
        PlayerProfileStore.INSTANCE.remember(uuid, day);
        PlayerProfileStore.INSTANCE.addMoney(uuid, PlayerGrantStore.STARTER_AMOUNT);
        PlayerGrantStore.INSTANCE.mark(uuid, PlayerGrantStore.STARTER_AMOUNT, day);
        player.displayClientMessage(Component.literal(
                "欢迎来到王国：领取启动资金 " + PlayerGrantStore.STARTER_AMOUNT + " 金币"), true);
    }
}
