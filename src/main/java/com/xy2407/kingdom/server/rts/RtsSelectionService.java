package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RTS 选中集合的服务端存档与基本校验：实体存在、是市民或本人替身、还活着。
 */
public final class RtsSelectionService {

    private static final Map<UUID, Set<UUID>> SELECTED = new ConcurrentHashMap<>();

    private RtsSelectionService() {
    }

    public static Set<UUID> getSelected(ServerPlayer player) {
        return SELECTED.getOrDefault(player.getUUID(), Set.of());
    }

    public static void setSelected(ServerPlayer player, Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            SELECTED.remove(player.getUUID());
        } else {
            SELECTED.put(player.getUUID(), Set.copyOf(ids));
        }
    }

    public static void clear(ServerPlayer player) {
        SELECTED.remove(player.getUUID());
    }

    public static void clearAll() {
        SELECTED.clear();
    }

    public static Set<UUID> filterControllable(ServerLevel level, ServerPlayer player, Collection<UUID> ids) {
        Set<UUID> allowed = new HashSet<>();
        if (ids == null) {
            return allowed;
        }
        for (UUID id : ids) {
            if (id != null && canControl(player, findEntity(level, id))) {
                allowed.add(id);
            }
        }
        return allowed;
    }

    public static boolean canControl(ServerPlayer player, Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof NpcEntity) {
            return true;
        }
        return entity instanceof RtsFakePlayerEntity fake
                && player.getUUID().equals(fake.getOwnerUUID());
    }

    public static Entity findEntity(ServerLevel level, UUID id) {
        if (id == null) {
            return null;
        }
        Entity entity = level.getEntity(id);
        if (entity != null) {
            return entity;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel other : server.getAllLevels()) {
            entity = other.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
