package com.xy2407.kingdom.server.building;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 建筑仓库存储：SQLite 是真源，AUI 的 SavedData 是唯一在跑的容器实例，进出各搬一次。
 */
public final class BuildingStorageStore {

    public static final BuildingStorageStore INSTANCE = new BuildingStorageStore();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String AUI_INVENTORY_KEY = "saved_data";

    private static final String DATA_NAME_PREFIX = "kingdom_building_storage_";

    private static final int FLUSH_INTERVAL_TICKS = 20;

    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastWritten = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> capacities = new ConcurrentHashMap<>();
    private int tickCounter;

    private BuildingStorageStore() {
    }

    public void reset() {
        loaded.clear();
        lastWritten.clear();
        capacities.clear();
        tickCounter = 0;
    }

    public static String dataName(UUID buildingId) {
        return DATA_NAME_PREFIX + buildingId.toString().replace("-", "");
    }

    public ItemStackHandler handlerOf(MinecraftServer server, UUID buildingId, int capacity) {
        if (server == null || buildingId == null) {
            return null;
        }
        ItemStackHandler base = baseOf(server, buildingId, capacity);
        if (base == null) {
            return null;
        }
        capacities.put(buildingId, Math.max(1, capacity));
        int slots = Math.max(1, Math.min(capacity, base.getSlots()));
        return slots == base.getSlots() ? base : new CapacityView(base, slots);
    }

    /**
     * 按建筑实际格数取容器；AUI 的 getOrCreate 在格数变化时会新建并逐槽搬迁，扩容不丢物品
     */
    private ItemStackHandler baseOf(MinecraftServer server, UUID buildingId, int capacity) {
        ApricitySavedData data = ApricitySavedData.get(server, dataName(buildingId));
        ItemStackHandler base = data.getOrCreate(AUI_INVENTORY_KEY, Math.max(1, capacity));
        if (loaded.add(buildingId)) {
            String snbt = readSnbt(buildingId);
            if (snbt != null && !snbt.isBlank()) {
                readSnbtInto(base, server.registryAccess(), snbt);
            }
            lastWritten.put(buildingId, snbt == null ? "" : snbt);
        }
        return base;
    }

    public void tick(MinecraftServer server) {
        if (server == null || loaded.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        if (++tickCounter < FLUSH_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        flushAll(server);
    }

    public void flushAll(MinecraftServer server) {
        if (server == null || loaded.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        for (UUID buildingId : loaded) {
            Integer capacity = capacities.get(buildingId);
            if (capacity == null || capacity <= 0) {
                continue;
            }
            ApricitySavedData data = ApricitySavedData.get(server, dataName(buildingId));
            ItemStackHandler base = data.getOrCreate(AUI_INVENTORY_KEY, capacity);
            flush(server, buildingId, base);
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

    private static final class CapacityView extends ItemStackHandler {

        private final ItemStackHandler delegate;
        private final int slots;

        private CapacityView(ItemStackHandler delegate, int slots) {
            super(0);
            this.delegate = delegate;
            this.slots = slots;
        }

        @Override
        public int getSlots() {
            return slots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return delegate.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return delegate.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return delegate.isItemValid(slot, stack);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            delegate.setStackInSlot(slot, stack);
        }
    }
}
