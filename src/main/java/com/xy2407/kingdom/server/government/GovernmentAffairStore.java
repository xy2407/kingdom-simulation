package com.xy2407.kingdom.server.government;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xy2407.kingdom.common.government.GovernmentAffair;
import com.xy2407.kingdom.common.persistence.GovernmentAffairSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;

import net.minecraft.server.MinecraftServer;

/**
 * 政务内存索引与落库：按领地分组，办理或超期即删除对应行
 */
public final class GovernmentAffairStore {
    public static final GovernmentAffairStore INSTANCE = new GovernmentAffairStore();

    public static final int MAX_PENDING_PER_TERRITORY = 12;

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<String, GovernmentAffair> byId = new LinkedHashMap<>();
    private final Map<String, List<GovernmentAffair>> byTerritory = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private GovernmentAffairStore() {
    }

    public void reset() {
        byId.clear();
        byTerritory.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void tick(MinecraftServer server) {
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

    public List<GovernmentAffair> of(String territoryId) {
        if (territoryId == null || territoryId.isBlank()) {
            return List.of();
        }
        List<GovernmentAffair> list = byTerritory.get(territoryId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public int countOf(String territoryId) {
        List<GovernmentAffair> list = byTerritory.get(territoryId);
        return list == null ? 0 : list.size();
    }

    public boolean add(GovernmentAffair affair) {
        if (affair == null || byId.containsKey(affair.id())) {
            return false;
        }
        if (countOf(affair.territoryId()) >= MAX_PENDING_PER_TERRITORY) {
            return false;
        }
        byId.put(affair.id(), affair);
        byTerritory.computeIfAbsent(affair.territoryId(), key -> new ArrayList<>()).add(affair);
        KingdomPersistence.submit(GovernmentAffairSchema.TABLE_AFFAIR, GovernmentAffairSchema.COL_UUID,
                affair.id(), toValues(affair));
        return true;
    }

    public boolean resolve(String territoryId, String affairId) {
        GovernmentAffair affair = affairId == null ? null : byId.get(affairId);
        if (affair == null || !affair.territoryId().equals(territoryId)) {
            return false;
        }
        remove(affair);
        return true;
    }

    public void removeAllOf(String territoryId) {
        List<GovernmentAffair> list = byTerritory.remove(territoryId);
        if (list == null) {
            return;
        }
        for (GovernmentAffair affair : list) {
            byId.remove(affair.id());
            KingdomPersistence.delete(GovernmentAffairSchema.TABLE_AFFAIR,
                    GovernmentAffairSchema.COL_UUID, affair.id());
        }
    }

    public int expireUpTo(int day) {
        List<GovernmentAffair> expired = new ArrayList<>();
        for (GovernmentAffair affair : byId.values()) {
            if (affair.expireDay() <= day) {
                expired.add(affair);
            }
        }
        for (GovernmentAffair affair : expired) {
            remove(affair);
        }
        return expired.size();
    }

    private void remove(GovernmentAffair affair) {
        byId.remove(affair.id());
        List<GovernmentAffair> list = byTerritory.get(affair.territoryId());
        if (list != null) {
            list.removeIf(item -> item.id().equals(affair.id()));
            if (list.isEmpty()) {
                byTerritory.remove(affair.territoryId());
            }
        }
        KingdomPersistence.delete(GovernmentAffairSchema.TABLE_AFFAIR,
                GovernmentAffairSchema.COL_UUID, affair.id());
    }

    private void load() {
        try {
            List<GovernmentAffair> rows = KingdomPersistence.querySync(connection -> {
                List<GovernmentAffair> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + GovernmentAffairSchema.columns()
                             + " FROM " + GovernmentAffairSchema.TABLE_AFFAIR + " ORDER BY "
                             + GovernmentAffairSchema.COL_CREATED_DAY)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            byId.clear();
            byTerritory.clear();
            for (GovernmentAffair affair : rows) {
                if (affair.territoryId().isBlank()) {
                    continue;
                }
                byId.put(affair.id(), affair);
                byTerritory.computeIfAbsent(affair.territoryId(), key -> new ArrayList<>()).add(affair);
            }
            loaded = true;
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
        }
    }

    private static Map<String, Object> toValues(GovernmentAffair affair) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(GovernmentAffairSchema.COL_UUID, affair.id());
        values.put(GovernmentAffairSchema.COL_TERRITORY_ID, affair.territoryId());
        values.put(GovernmentAffairSchema.COL_TYPE, affair.type());
        values.put(GovernmentAffairSchema.COL_CREATED_DAY, affair.createdDay());
        values.put(GovernmentAffairSchema.COL_EXPIRE_DAY, affair.expireDay());
        values.put(GovernmentAffairSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static GovernmentAffair readRow(ResultSet rs) throws SQLException {
        return new GovernmentAffair(
                rs.getString(GovernmentAffairSchema.COL_UUID),
                rs.getString(GovernmentAffairSchema.COL_TERRITORY_ID),
                rs.getString(GovernmentAffairSchema.COL_TYPE),
                rs.getInt(GovernmentAffairSchema.COL_CREATED_DAY),
                rs.getInt(GovernmentAffairSchema.COL_EXPIRE_DAY));
    }
}
