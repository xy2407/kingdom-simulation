package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * RTS 替身生命周期：生成、回收、直接清理与停服兜底清理。
 */
public final class RtsFakePlayerService {

    private RtsFakePlayerService() {
    }

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

    public static void recallFor(ServerPlayer player) {
        RtsFakePlayerEntity fake = findFor(player);
        if (fake != null && fake.level() instanceof ServerLevel level) {
            player.teleportTo(level, fake.getX(), fake.getY(), fake.getZ(),
                    player.getYRot(), player.getXRot());
            fake.discard();
        }
        RtsSelectionService.clear(player);
    }

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
