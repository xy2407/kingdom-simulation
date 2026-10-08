package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NationSchema;

/**
 * 国家登记表：只管玩家自建的国家；预置阵营由客户端硬编码，不落库。
 */
public final class NationRegistry {

    public static final NationRegistry INSTANCE = new NationRegistry();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    public static final String PLAYER_ID_PREFIX = "realm-";

    private static final Set<String> BUILTIN_IDS = Set.of(
            "valarian", "bohemian", "caladrian", "numerian", "visgothian", "galician",
            "lancastian", "hospitaller", "templar", "barathian", "orleanian", "burgundian");

    private final Map<String, Nation> byId = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private NationRegistry() {
    }

    public void reset() {
        byId.clear();
        loaded = false;
        loadRetryCooldown = 0;
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
            List<Nation> rows = KingdomPersistence.querySync(connection -> {
                List<Nation> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + NationSchema.columns()
                             + " FROM " + NationSchema.TABLE_NATION)) {
                    while (rs.next()) {
                        try {
                            Nation nation = readRow(rs);
                            if (nation.id() == null || nation.id().isBlank()) {
                                skipped[0]++;
                                continue;
                            }
                            result.add(nation);
                        } catch (RuntimeException brokenRow) {
                            skipped[0]++;
                        }
                    }
                }
                return result;
            });
            byId.clear();
            for (Nation nation : rows) {
                byId.put(nation.id(), nation);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 个玩家自建国家{}", byId.size(),
                    skipped[0] > 0 ? "（跳过 " + skipped[0] + " 行废数据）" : "");
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载国家数据失败", exception);
        }
    }

    public List<Nation> playerNations() {
        return List.copyOf(byId.values());
    }

    public Nation byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public boolean isKnown(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return BUILTIN_IDS.contains(id) || byId.containsKey(id);
    }

    public static boolean isBuiltin(String id) {
        return id != null && BUILTIN_IDS.contains(id);
    }

    public Nation create(String name, String ruler, String motto,
                         String colorLeft, String colorRight,
                         String emblem, String emblemColor, UUID founder, int day) {
        Nation nation = new Nation(nextId(), name, ruler, motto, colorLeft, colorRight,
                emblem, emblemColor, founder == null ? "" : founder.toString(), true, day);
        byId.put(nation.id(), nation);
        KingdomPersistence.submit(NationSchema.TABLE_NATION, NationSchema.COL_ID,
                nation.id(), toValues(nation));
        return nation;
    }

    private String nextId() {
        int max = 0;
        for (String id : byId.keySet()) {
            if (id.startsWith(PLAYER_ID_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(PLAYER_ID_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return PLAYER_ID_PREFIX + (max + 1);
    }

    private static Nation readRow(ResultSet rs) throws SQLException {
        return new Nation(
                rs.getString(NationSchema.COL_ID),
                rs.getString(NationSchema.COL_NAME),
                rs.getString(NationSchema.COL_RULER),
                rs.getString(NationSchema.COL_MOTTO),
                rs.getString(NationSchema.COL_COLOR_LEFT),
                rs.getString(NationSchema.COL_COLOR_RIGHT),
                rs.getString(NationSchema.COL_EMBLEM),
                rs.getString(NationSchema.COL_EMBLEM_COLOR),
                rs.getString(NationSchema.COL_FOUNDER_UUID),
                rs.getInt(NationSchema.COL_PERMANENT) != 0,
                rs.getInt(NationSchema.COL_CREATED_DAY));
    }

    private static Map<String, Object> toValues(Nation nation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(NationSchema.COL_NAME, nation.name());
        values.put(NationSchema.COL_RULER, nation.ruler());
        values.put(NationSchema.COL_MOTTO, nation.motto());
        values.put(NationSchema.COL_COLOR_LEFT, nation.colorLeft());
        values.put(NationSchema.COL_COLOR_RIGHT, nation.colorRight());
        values.put(NationSchema.COL_EMBLEM, nation.emblem());
        values.put(NationSchema.COL_EMBLEM_COLOR, nation.emblemColor());
        values.put(NationSchema.COL_FOUNDER_UUID, nation.founderUuid());
        values.put(NationSchema.COL_PERMANENT, nation.permanent() ? 1 : 0);
        values.put(NationSchema.COL_CREATED_DAY, nation.createdDay());
        values.put(NationSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
