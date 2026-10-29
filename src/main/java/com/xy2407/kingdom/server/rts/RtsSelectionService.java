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
            return territory != null && player.getUUID().equals(territory.owner());
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
