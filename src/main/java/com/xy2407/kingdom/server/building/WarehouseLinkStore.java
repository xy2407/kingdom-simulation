package com.xy2407.kingdom.server.building;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.WarehouseLinkSchema;

/**
 * 仓库归属：建筑 → 仓库的唯一绑定，落库在 warehouse_link 表。
 * 内存只做读写缓存；未绑定建筑由调用方按坐标就近选仓库。
 */
public final class WarehouseLinkStore {

    public static final WarehouseLinkStore INSTANCE = new WarehouseLinkStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private static final int SWEEP_INTERVAL_TICKS = 1200;

    /** 建筑 id → 仓库 id。 */
    private final Map<UUID, UUID> links = new ConcurrentHashMap<>();

    private boolean loaded;

    private int loadRetryCooldown;

    private int sweepTimer;

    private WarehouseLinkStore() {
    }

    public void reset() {
        links.clear();
        loaded = false;
        loadRetryCooldown = 0;
        sweepTimer = 0;
    }

    /** 每 tick 调：数据库/建筑表就绪后加载一次，之后定期清理失效绑定。 */
    public void tick() {
        if (!loaded) {
            if (loadRetryCooldown > 0) {
                loadRetryCooldown--;
                return;
            }
            load();
            return;
        }
        if (++sweepTimer % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }
        for (UUID buildingId : new ArrayList<>(links.keySet())) {
            UUID warehouseId = links.get(buildingId);
            if (warehouseId == null || !isValid(buildingId, warehouseId)) {
                unlink(buildingId);
            }
        }
    }

    /** 建筑归属的仓库；绑定失效（建筑/仓库没了、不再是仓库、跨维度）会顺手清掉。 */
    public UUID warehouseOf(UUID buildingId) {
        if (buildingId == null) {
            return null;
        }
        UUID warehouseId = links.get(buildingId);
        if (warehouseId == null) {
            return null;
        }
        if (!isValid(buildingId, warehouseId)) {
            unlink(buildingId);
            return null;
        }
        return warehouseId;
    }

    /** 某仓库管辖的全部建筑。 */
    public List<UUID> buildingsOf(UUID warehouseId) {
        List<UUID> result = new ArrayList<>();
        if (warehouseId == null) {
            return result;
        }
        for (Map.Entry<UUID, UUID> entry : links.entrySet()) {
            if (warehouseId.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 绑定建筑到仓库；已绑到别的仓库时直接改绑。 */
    public boolean link(UUID warehouseId, UUID buildingId) {
        if (warehouseId == null || buildingId == null || warehouseId.equals(buildingId)) {
            return false;
        }
        BuildingRecord warehouse = BuildingRegistry.INSTANCE.byId(warehouseId);
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(buildingId);
        if (!isWarehouse(warehouse) || building == null || isWarehouse(building)) {
            return false;
        }
        if (!warehouse.dimension().equals(building.dimension())) {
            return false;
        }
        UUID previous = links.put(buildingId, warehouseId);
        saveRow(buildingId, warehouseId, previous == null || !previous.equals(warehouseId));
        return previous == null || !previous.equals(warehouseId);
    }

    /** 解除某个建筑的绑定并删掉数据库行。 */
    public void unlink(UUID buildingId) {
        if (buildingId == null) {
            return;
        }
        links.remove(buildingId);
        KingdomPersistence.delete(WarehouseLinkSchema.TABLE_WAREHOUSE_LINK,
                WarehouseLinkSchema.COL_BUILDING_ID, buildingId.toString());
    }

    /** 仓库不再是仓库 / 被移除：把它管辖的全部建筑解绑并清库。 */
    public void clearWarehouse(UUID warehouseId) {
        if (warehouseId == null) {
            return;
        }
        for (UUID buildingId : buildingsOf(warehouseId)) {
            unlink(buildingId);
        }
    }

    /** 仅当该建筑确实绑在这个仓库名下时才解绑，供界面取消勾选用。 */
    public void unlinkFrom(UUID warehouseId, UUID buildingId) {
        if (warehouseId == null || buildingId == null) {
            return;
        }
        if (warehouseId.equals(links.get(buildingId))) {
            unlink(buildingId);
        }
    }

    private boolean isValid(UUID buildingId, UUID warehouseId) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(buildingId);
        BuildingRecord warehouse = BuildingRegistry.INSTANCE.byId(warehouseId);
        return building != null && !isWarehouse(building) && isWarehouse(warehouse)
                && warehouse.dimension().equals(building.dimension());
    }

    private static boolean isWarehouse(BuildingRecord record) {
        if (record == null) {
            return false;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(record);
        return meta != null && meta.isWork(BuildingWork.WAREHOUSE);
    }

    private void load() {
        if (!KingdomPersistence.isReady() || !BuildingRegistry.INSTANCE.isLoaded()) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            return;
        }
        try {
            Map<UUID, UUID> rows = KingdomPersistence.querySync(connection -> {
                Map<UUID, UUID> result = new HashMap<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT "
                             + WarehouseLinkSchema.COL_BUILDING_ID + ", "
                             + WarehouseLinkSchema.COL_WAREHOUSE_ID
                             + " FROM " + WarehouseLinkSchema.TABLE_WAREHOUSE_LINK)) {
                    while (rs.next()) {
                        try {
                            result.put(UUID.fromString(rs.getString(WarehouseLinkSchema.COL_BUILDING_ID)),
                                    UUID.fromString(rs.getString(WarehouseLinkSchema.COL_WAREHOUSE_ID)));
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
                return result;
            });
            int dropped = 0;
            for (Map.Entry<UUID, UUID> entry : rows.entrySet()) {
                if (isValid(entry.getKey(), entry.getValue())) {
                    links.put(entry.getKey(), entry.getValue());
                } else {
                    dropped++;
                    KingdomPersistence.delete(WarehouseLinkSchema.TABLE_WAREHOUSE_LINK,
                            WarehouseLinkSchema.COL_BUILDING_ID, entry.getKey().toString());
                }
            }
            loaded = true;
            if (!links.isEmpty() || dropped > 0) {
                ModMain.LOGGER.info("仓库归属已加载：{} 条有效，清理 {} 条失效", links.size(), dropped);
            }
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载仓库归属失败", exception);
        }
    }

    private static void saveRow(UUID buildingId, UUID warehouseId, boolean resetCreatedAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WarehouseLinkSchema.COL_BUILDING_ID, buildingId.toString());
        values.put(WarehouseLinkSchema.COL_WAREHOUSE_ID, warehouseId.toString());
        if (resetCreatedAt) {
            values.put(WarehouseLinkSchema.COL_CREATED_AT, System.currentTimeMillis());
        }
        values.put(WarehouseLinkSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(WarehouseLinkSchema.TABLE_WAREHOUSE_LINK,
                WarehouseLinkSchema.COL_BUILDING_ID, buildingId.toString(), values);
    }
}
