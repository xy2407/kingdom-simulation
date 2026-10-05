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
 * 领地民心三值的内存索引与落库入口。
 *
 * <p>模式照 {@code TerritoryRegistry} / {@code PlayerProfileStore}：懒加载（等
 * {@code KingdomPersistence} 就绪后读一次），之后全部走内存，变更用
 * {@link KingdomPersistence#submit} 攒增量写库。</p>
 *
 * <p>取值一律用 {@link #of(String)}：库里没有这块领地的行就按默认值新建一条
 * （凝聚力 100 / 忠诚 80 / 幸福 80）并立即落库，所以调用方不必关心"有没有"。</p>
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

    /** 懒加载驱动：DB 就绪后读一次，之后不再重复。 */
    public void tick() {
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
    }

    /**
     * 取这块领地的民心值；没有就按默认值建一条并落库。
     *
     * <p>注意不能写成 {@code computeIfAbsent(...)} 里直接落库——那会在 map 的
     * 分段锁里做 IO，容易和别的写操作互相等。这里先查、再放。</p>
     */
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

    /** 只查、不新建（给"有没有"这类判断用）。 */
    public TerritoryVitals peek(String territoryId) {
        return territoryId == null ? null : byTerritory.get(territoryId);
    }

    /** 落库一条。所有改动三值的地方改完都要调它。 */
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
