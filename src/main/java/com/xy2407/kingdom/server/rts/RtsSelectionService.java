package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.entity.RtsFakePlayerEntity;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

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
    private static final Set<UUID> SELECTED_NPCS = ConcurrentHashMap.newKeySet();

    private RtsSelectionService() {
    }

    public static boolean isSelectedNpc(UUID entityId) {
        return entityId != null && SELECTED_NPCS.contains(entityId);
    }

    private static void rebuildSelectedNpcs() {
        SELECTED_NPCS.clear();
        for (Set<UUID> ids : SELECTED.values()) {
            SELECTED_NPCS.addAll(ids);
        }
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
        rebuildSelectedNpcs();
    }

    public static void clear(ServerPlayer player) {
        SELECTED.remove(player.getUUID());
        rebuildSelectedNpcs();
    }

    /** 实体死亡/被移除：从所有玩家的选中集合里摘掉，避免残留幽灵 UUID。 */
    public static void onEntityRemoved(UUID entityId) {
        if (entityId == null) {
            return;
        }
        java.util.Set<UUID> affected = new java.util.HashSet<>();
        for (Map.Entry<UUID, Set<UUID>> entry : SELECTED.entrySet()) {
            if (entry.getValue().contains(entityId)) {
                affected.add(entry.getKey());
            }
        }
        for (UUID playerId : affected) {
            Set<UUID> ids = new HashSet<>(SELECTED.getOrDefault(playerId, Set.of()));
            ids.remove(entityId);
            if (ids.isEmpty()) {
                SELECTED.remove(playerId);
            } else {
                SELECTED.put(playerId, Set.copyOf(ids));
            }
        }
        rebuildSelectedNpcs();
    }

    public static void clearAll() {
        SELECTED.clear();
        SELECTED_NPCS.clear();
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
        if (entity == null || !entity.isAlive() || player == null) {
            return false;
        }
        if (entity instanceof NpcEntity npc) {
            return canControlNpc(player, npc);
        }
        return entity instanceof RtsFakePlayerEntity fake
                && player.getUUID().equals(fake.getOwnerUUID());
    }

    public static boolean canControlNpc(ServerPlayer player, NpcEntity npc) {
        if (player == null || npc == null || npc.isTouristEntity()) {
            return false;
        }
        if (npc.getCitizenId() == null) {
            return true;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data == null) {
            return true;
        }
        String ownerId = data.getOwnerId();
        if (!ownerId.isBlank()) {
            return ownerId.equals(player.getUUID().toString());
        }
        String territoryId = data.getTerritoryId();
        if (!territoryId.isBlank()) {
            Territory territory = TerritoryRegistry.INSTANCE.byId(territoryId);
            if (territory == null) {
                // 领土注册表还没加载完（重进世界/刚开服）或该领土已被删除时，不能把市民锁死：
                // 以前这里直接返回 false，客户端每次框选都会被纠正包清空，表现就是"退出一次之后
                // 自家 NPC 再也选不中"。owner 已经空着，这里按无主处理放行。
                return true;
            }
            return player.getUUID().equals(territory.owner());
        }
        return true;
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
