package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 玩家档案表迁移：首登标记、归属（国家与领地）以及上次看地图的中心点。
 */
public final class PlayerProfileSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_PLAYER_PROFILE = "player_profile";
    public static final String COL_UUID = "uuid";
    public static final String COL_NATION_ID = "nation_id";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_ONBOARDED = "onboarded";
    public static final String COL_FIRST_JOIN_DAY = "first_join_day";
    public static final String COL_MAP_CENTER_X = "map_center_x";
    public static final String COL_MAP_CENTER_Z = "map_center_z";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 15;
    }

    @Override
    public String tableName() {
        return TABLE_PLAYER_PROFILE;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_UUID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_PLAYER_PROFILE + " (");
        boolean first = true;
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append(column.getKey()).append(' ').append(column.getValue());
        }
        return sql.append(')').toString();
    }

    private static void addMissingColumns(Connection connection) throws SQLException {
        Set<String> existing = new LinkedHashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_PLAYER_PROFILE + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_PLAYER_PROFILE + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_PLAYER_PROFILE + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_NATION_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_ONBOARDED, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_FIRST_JOIN_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAP_CENTER_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAP_CENTER_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_NATION_ID, COL_TERRITORY_ID, COL_ONBOARDED,
                COL_FIRST_JOIN_DAY, COL_MAP_CENTER_X, COL_MAP_CENTER_Z, COL_UPDATED_AT
        });
    }
}
