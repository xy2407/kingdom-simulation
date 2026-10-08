package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.TerritorySchema;

import net.minecraft.core.BlockPos;

/**
 * 领地登记表：以市政厅所在区块为中心的 5×5 区块，一个玩家只能有一块。
 */
public final class TerritoryRegistry {

    public static final TerritoryRegistry INSTANCE = new TerritoryRegistry();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final String ID_PREFIX = "territory-";

    private final Map<String, Territory> byId = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private TerritoryRegistry() {
    }

    public void reset() {
        byId.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void tick() {
        if (loaded) {
            return;
        }
        if (loadRetryCooldown > 0) {
            loadRetryCooldown--;
            return;
        }
        if (KingdomPersistence.isReady()) {
            load();
        }
    }

    private void load() {
        try {
            int[] skipped = {0};
            List<Territory> rows = KingdomPersistence.querySync(connection -> {
                List<Territory> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + TerritorySchema.columns()
                             + " FROM " + TerritorySchema.TABLE_TERRITORY)) {
                    while (rs.next()) {
                        try {
                            Territory territory = readRow(rs);
                            if (territory.id() == null || territory.id().isBlank()) {
                                skipped[0]++;
                                continue;
                            }
                            result.add(territory);
                        } catch (RuntimeException brokenRow) {
                            skipped[0]++;
                        }
                    }
                }
                return result;
            });
            byId.clear();
            for (Territory territory : rows) {
                byId.put(territory.id(), territory);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 块领地{}", byId.size(),
                    skipped[0] > 0 ? "（跳过 " + skipped[0] + " 行废数据）" : "");
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载领地数据失败", exception);
        }
    }

    public List<Territory> all() {
        return List.copyOf(byId.values());
    }

    public Territory byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public Territory byOwner(UUID owner) {
        if (owner == null) {
            return null;
        }
        for (Territory territory : byId.values()) {
            if (owner.equals(territory.owner())) {
                return territory;
            }
        }
        return null;
    }

    public Territory byChunk(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return null;
        }
        for (Territory territory : byId.values()) {
            if (dimension.equals(territory.dimension()) && territory.containsChunk(chunkX, chunkZ)) {
                return territory;
            }
        }
        return null;
    }

    public Territory byBlock(String dimension, double x, double z) {
        return byChunk(dimension,
                (int) Math.floor(x / 16.0),
                (int) Math.floor(z / 16.0));
    }

    public Territory create(UUID owner, String nationId, String name,
                            String dimension, BlockPos center, int day) {
        if (owner == null || byOwner(owner) != null) {
            return null;
        }
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int radius = Territory.CHUNK_RADIUS;

        Territory territory = new Territory(
                nextId(), name, nationId == null ? "" : nationId, owner, dimension,
                center.getX(), center.getZ(),
                centerChunkX - radius, centerChunkZ - radius,
                centerChunkX + radius, centerChunkZ + radius,
                day);
        byId.put(territory.id(), territory);
        KingdomPersistence.submit(TerritorySchema.TABLE_TERRITORY, TerritorySchema.COL_ID,
                territory.id(), toValues(territory));
        return territory;
    }

    private String nextId() {
        int max = 0;
        for (String id : byId.keySet()) {
            if (id.startsWith(ID_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(ID_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ID_PREFIX + (max + 1);
    }

    private static Territory readRow(ResultSet rs) throws SQLException {
        String owner = rs.getString(TerritorySchema.COL_OWNER_UUID);
        UUID ownerId;
        try {
            ownerId = owner == null || owner.isBlank() ? null : UUID.fromString(owner);
        } catch (IllegalArgumentException exception) {
            ownerId = null;
        }
        return new Territory(
                rs.getString(TerritorySchema.COL_ID),
                rs.getString(TerritorySchema.COL_NAME),
                rs.getString(TerritorySchema.COL_NATION_ID),
                ownerId,
                rs.getString(TerritorySchema.COL_DIMENSION),
                rs.getInt(TerritorySchema.COL_CENTER_X),
                rs.getInt(TerritorySchema.COL_CENTER_Z),
                rs.getInt(TerritorySchema.COL_MIN_CHUNK_X),
                rs.getInt(TerritorySchema.COL_MIN_CHUNK_Z),
                rs.getInt(TerritorySchema.COL_MAX_CHUNK_X),
                rs.getInt(TerritorySchema.COL_MAX_CHUNK_Z),
                rs.getInt(TerritorySchema.COL_CREATED_DAY));
    }

    private static Map<String, Object> toValues(Territory territory) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TerritorySchema.COL_NAME, territory.name());
        values.put(TerritorySchema.COL_NATION_ID, territory.nationId());
        values.put(TerritorySchema.COL_OWNER_UUID,
                territory.owner() == null ? "" : territory.owner().toString());
        values.put(TerritorySchema.COL_DIMENSION, territory.dimension());
        values.put(TerritorySchema.COL_CENTER_X, territory.centerX());
        values.put(TerritorySchema.COL_CENTER_Z, territory.centerZ());
        values.put(TerritorySchema.COL_MIN_CHUNK_X, territory.minChunkX());
        values.put(TerritorySchema.COL_MIN_CHUNK_Z, territory.minChunkZ());
        values.put(TerritorySchema.COL_MAX_CHUNK_X, territory.maxChunkX());
        values.put(TerritorySchema.COL_MAX_CHUNK_Z, territory.maxChunkZ());
        values.put(TerritorySchema.COL_CREATED_DAY, territory.createdDay());
        values.put(TerritorySchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
