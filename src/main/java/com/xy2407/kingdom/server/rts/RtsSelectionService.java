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
 * RTS 选中集合的服务端存档 + 基本校验。
 *
 * <p>移植自 {@code xy's_nsuk} 的 {@code RtsSelectionSyncPacket} / {@code RtsSelectionRequestPacket}
 * 两个 handler 里的过滤逻辑，但把城市归属校验（
 * {@code CitizenService.findCitizen} + {@code CityService.canManageCity}）整块换成了三条与阵营无关的
 * 基本检查：实体存在、是 {@link NpcEntity} 或**本人的**替身、还活着。</p>
 */
public final class RtsSelectionService {

    private static final Map<UUID, Set<UUID>> SELECTED = new ConcurrentHashMap<>();

    private RtsSelectionService() {
    }

    /** 服务端认定的该玩家当前选中集合。 */
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

    /** 把客户端声称的选中集合过滤成服务端认可的集合。 */
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

    /** 三条与阵营无关的基本检查。 */
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

    /** 先在本维度找，找不到再跨维度兜底（实体换维度后 UUID 查询仍要能命中）。 */
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
