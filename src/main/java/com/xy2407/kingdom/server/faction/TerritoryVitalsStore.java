package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xy2407.kingdom.common.faction.TerritoryVitals;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema;

/**
 * 领地民心三值的内存索引与落库入口：懒加载后全走内存，变更攒增量写库。
 */
public final class TerritoryVitalsStore {

    public static final TerritoryVitalsStore INSTANCE = new TerritoryVitalsStore();

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, TerritoryVitals> byTerritory = new ConcurrentHashMap<>();
    private boolean loaded;

    private TerritoryVitalsStore() {
    }

    public void reset() {
        byTerritory.clear();
        loaded = false;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void tick() {
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
    }

    public TerritoryVitals of(String territoryId) {
        if (territoryId == null || territoryId.isBlank()) {
            return null;
        }
        TerritoryVitals existing = byTerritory.get(territoryId);
        if (existing != null) {
            return existing;
        }
        TerritoryVitals fresh = new TerritoryVitals(territoryId);
        TerritoryVitals raced = byTerritory.putIfAbsent(territoryId, fresh);
        if (raced != null) {
            return raced;
        }
        persist(fresh);
        return fresh;
    }

    public TerritoryVitals peek(String territoryId) {
        return territoryId == null ? null : byTerritory.get(territoryId);
    }

    public void persist(TerritoryVitals vitals) {
        if (vitals == null || !KingdomPersistence.isReady()) {
            return;
        }
        KingdomPersistence.submit(TerritoryVitalsSchema.TABLE_TERRITORY_VITALS,
                TerritoryVitalsSchema.COL_TERRITORY_ID, vitals.territoryId(), vitals.toValues());
    }

    private void load() {
        try {
            KingdomPersistence.querySync(connection -> {
                int count = 0;
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + TerritoryVitalsSchema.columns()
                             + " FROM " + TerritoryVitalsSchema.TABLE_TERRITORY_VITALS)) {
                    while (rs.next()) {
                        TerritoryVitals vitals = mapRow(rs);
                        byTerritory.put(vitals.territoryId(), vitals);
                        count++;
                    }
                }
                return count;
            });
            loaded = true;
            LOGGER.info("已加载 {} 块领地的民心数据", byTerritory.size());
        } catch (Exception e) {
            LOGGER.error("加载领地民心数据失败", e);
        }
    }

    private static TerritoryVitals mapRow(ResultSet rs) throws SQLException {
        return new TerritoryVitals(
                rs.getString(TerritoryVitalsSchema.COL_TERRITORY_ID),
                rs.getInt(TerritoryVitalsSchema.COL_UNITY),
                rs.getInt(TerritoryVitalsSchema.COL_LOYALTY),
                rs.getInt(TerritoryVitalsSchema.COL_HAPPINESS),
                rs.getString(TerritoryVitalsSchema.COL_WAR_ENEMY_ID),
                rs.getInt(TerritoryVitalsSchema.COL_WAR_START_DAY),
                rs.getInt(TerritoryVitalsSchema.COL_WAR_SELF_POP),
                rs.getInt(TerritoryVitalsSchema.COL_WAR_ENEMY_POP),
                rs.getInt(TerritoryVitalsSchema.COL_WAR_AGGRESSOR) != 0);
    }
}
