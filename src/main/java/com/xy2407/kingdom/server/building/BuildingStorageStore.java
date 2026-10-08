package com.xy2407.kingdom.server.building;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sighs.apricityui.config.ApricitySavedData;
import com.xy2407.kingdom.common.persistence.BuildingStorageSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 建筑仓库存储：SQLite 是真源，AUI 的 SavedData 只是会话载体，进出各搬一次。
 */
public final class BuildingStorageStore {

    public static final BuildingStorageStore INSTANCE = new BuildingStorageStore();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String AUI_INVENTORY_KEY = "saved_data";

    private static final String DATA_NAME_PREFIX = "kingdom_building_storage_";

    private static final int FLUSH_INTERVAL_TICKS = 60;

    private final Map<UUID, ItemStackHandler> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastWritten = new ConcurrentHashMap<>();
    private int tickCounter;

    private BuildingStorageStore() {
    }

    public void reset() {
        handlers.clear();
        lastWritten.clear();
        tickCounter = 0;
    }

    public static String dataName(UUID buildingId) {
        return DATA_NAME_PREFIX + buildingId.toString().replace("-", "");
    }

    public ItemStackHandler handlerOf(MinecraftServer server, UUID buildingId, int capacity) {
        if (server == null || buildingId == null) {
            return null;
        }
        int slots = Math.max(1, capacity);
        ItemStackHandler cached = handlers.get(buildingId);
        if (cached != null && cached.getSlots() == slots) {
            return cached;
        }

        ApricitySavedData data = ApricitySavedData.get(server, dataName(buildingId));
        ItemStackHandler handler = data.getOrCreate(AUI_INVENTORY_KEY, slots);
        if (cached == null) {
            String snbt = readSnbt(buildingId);
            if (snbt != null && !snbt.isBlank()) {
                readSnbtInto(handler, server.registryAccess(), snbt);
            }
            lastWritten.put(buildingId, snbt == null ? "" : snbt);
        }
        handlers.put(buildingId, handler);
        return handler;
    }

    public void tick(MinecraftServer server) {
        if (server == null || handlers.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        if (++tickCounter < FLUSH_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        flushAll(server);
    }

    public void flushAll(MinecraftServer server) {
        if (server == null || handlers.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        for (Map.Entry<UUID, ItemStackHandler> entry : handlers.entrySet()) {
            flush(server, entry.getKey(), entry.getValue());
        }
    }

    private void flush(MinecraftServer server, UUID buildingId, ItemStackHandler handler) {
        String snbt = toSnbt(handler, server.registryAccess());
        String previous = lastWritten.get(buildingId);
        if (snbt.equals(previous == null ? "" : previous)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(BuildingStorageSchema.COL_INVENTORY, snbt);
        values.put(BuildingStorageSchema.COL_CAPACITY, handler.getSlots());
        values.put(BuildingStorageSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(BuildingStorageSchema.TABLE_BUILDING_STORAGE,
                BuildingStorageSchema.COL_BUILDING_ID, buildingId.toString(), values);
        lastWritten.put(buildingId, snbt);
    }

    private static String readSnbt(UUID buildingId) {
        try {
            return KingdomPersistence.querySync(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT " + BuildingStorageSchema.COL_INVENTORY
                                + " FROM " + BuildingStorageSchema.TABLE_BUILDING_STORAGE
                                + " WHERE " + BuildingStorageSchema.COL_BUILDING_ID + " = ?")) {
                    ps.setString(1, buildingId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        } catch (SQLException failure) {
            LOGGER.warn("读取建筑仓库失败 building={}", buildingId, failure);
            return null;
        }
    }

    private static String toSnbt(ItemStackHandler handler, HolderLookup.Provider registries) {
        CompoundTag tag = handler.serializeNBT(registries);
        return tag == null || tag.isEmpty() ? "" : tag.toString();
    }

    private static void readSnbtInto(ItemStackHandler handler, HolderLookup.Provider registries, String snbt) {
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            handler.deserializeNBT(registries, tag);
        } catch (Exception invalid) {
            LOGGER.warn("建筑仓库数据解析失败，按空仓库处理", invalid);
        }
    }
}
