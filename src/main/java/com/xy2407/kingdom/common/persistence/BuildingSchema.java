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
 * 建筑表迁移：建 building 表、幂等补列，迁移号为全局线性须取当前最大值
 */
public final class BuildingSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_BUILDING = "building";
    public static final String COL_UUID = "uuid";
    public static final String COL_CODE = "code";
    public static final String COL_NAME = "name";
    public static final String COL_TYPE = "type";
    public static final String COL_CATEGORY = "category";
    public static final String COL_TAGS = "tags";
    public static final String COL_NATION = "nation";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_MIN_X = "min_x";
    public static final String COL_MIN_Y = "min_y";
    public static final String COL_MIN_Z = "min_z";
    public static final String COL_MAX_X = "max_x";
    public static final String COL_MAX_Y = "max_y";
    public static final String COL_MAX_Z = "max_z";
    public static final String COL_LEVEL = "level";
    public static final String COL_BUILD_STATE = "build_state";
    public static final String COL_STRUCTURE_NAME = "structure_name";
    public static final String COL_ROTATION = "rotation";
    public static final String COL_RESIDENT_IDS = "resident_ids";
    public static final String COL_WORKER_IDS = "worker_ids";
    public static final String COL_FARM_SLOTS = "farm_slots";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 7;
    }

    @Override
    public String tableName() {
        return TABLE_BUILDING;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_BUILDING + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_BUILDING + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_BUILDING + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_BUILDING + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_BUILDING + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_CODE, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TYPE, "TEXT NOT NULL DEFAULT 'RESIDENTIAL'");
        defs.put(COL_CATEGORY, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TAGS, "TEXT NOT NULL DEFAULT '[]'");
        defs.put(COL_NATION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TERRITORY_ID, "TEXT");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MIN_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_LEVEL, "INTEGER NOT NULL DEFAULT 1");
        defs.put(COL_BUILD_STATE, "TEXT NOT NULL DEFAULT 'COMPLETED'");
        defs.put(COL_STRUCTURE_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_ROTATION, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_RESIDENT_IDS, "TEXT NOT NULL DEFAULT '[]'");
        defs.put(COL_WORKER_IDS, "TEXT NOT NULL DEFAULT '[]'");
        defs.put(COL_FARM_SLOTS, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_CODE, COL_NAME, COL_TYPE, COL_CATEGORY, COL_TAGS, COL_NATION,
                COL_TERRITORY_ID, COL_DIMENSION, COL_MIN_X, COL_MIN_Y, COL_MIN_Z,
                COL_MAX_X, COL_MAX_Y, COL_MAX_Z, COL_LEVEL, COL_BUILD_STATE, COL_STRUCTURE_NAME,
                COL_ROTATION, COL_RESIDENT_IDS, COL_WORKER_IDS, COL_FARM_SLOTS,
                COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
