package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * RTS 替身的生命周期：生成 / 回收（把玩家挪到替身处）/ 直接清理 / 兜底清理。
 *
 * <p>对应 {@code xy's_nsuk} 的 {@code RtsFakePlayerSpawnPacket} + {@code RtsFakePlayerSyncService}
 * + {@code RtsServerTick} 里的清理段。nsuk 是每 10 tick 把玩家传送到替身处（替身当锚点），
 * 这里改成**只在退出时**把玩家安置到替身最后的位置 —— 半径更小、也省掉了每 tick 改重力/无敌的折腾。</p>
 */
public final class RtsFakePlayerService {

    private RtsFakePlayerService() {
    }

    /** 在玩家脚下放一个替身（先回收旧的，重复调用等价于"把替身挪回脚下"）。 */
    public static RtsFakePlayerEntity spawnFor(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        discardFor(player);
        RtsFakePlayerEntity fake = new RtsFakePlayerEntity(ModEntities.RTS_FAKE_PLAYER.get(), level);
        fake.setPos(player.getX(), player.getY(), player.getZ());
        fake.setOwnerUUID(player.getUUID());
        float yaw = player.getYRot();
        fake.setYRot(yaw);
        fake.setYHeadRot(yaw);
        fake.yBodyRot = yaw;
        fake.setCustomName(player.getName());
        fake.setCustomNameVisible(true);
        level.addFreshEntity(fake);
        return fake;
    }

    /** 退出 RTS：玩家本体落到替身最后站的位置，替身消失。 */
    public static void recallFor(ServerPlayer player) {
        RtsFakePlayerEntity fake = findFor(player);
        if (fake != null && fake.level() instanceof ServerLevel level) {
            player.teleportTo(level, fake.getX(), fake.getY(), fake.getZ(),
                    player.getYRot(), player.getXRot());
            fake.discard();
        }
        RtsSelectionService.clear(player);
    }

    /** 直接清掉替身，不动玩家位置（死亡 / 掉线 / 换维度时用）。 */
    public static void clearFor(ServerPlayer player) {
        discardFor(player);
        RtsSelectionService.clear(player);
    }

    public static RtsFakePlayerEntity findFor(ServerPlayer player) {
        return findFor(player.getServer(), player.getUUID());
    }

    public static RtsFakePlayerEntity findFor(MinecraftServer server, java.util.UUID owner) {
        if (server == null || owner == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof RtsFakePlayerEntity fake && owner.equals(fake.getOwnerUUID())) {
                    return fake;
                }
            }
        }
        return null;
    }

    /** 服务端整体收尾：清掉所有替身与选中集合。 */
    public static void clearAll(MinecraftServer server) {
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof RtsFakePlayerEntity) {
                        entity.discard();
                    }
                }
            }
        }
        RtsSelectionService.clearAll();
    }

    private static void discardFor(ServerPlayer player) {
        RtsFakePlayerEntity fake = findFor(player);
        if (fake != null) {
            fake.discard();
        }
    }
}
