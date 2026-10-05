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
 * 农田表迁移：建 farm_plot 表并幂等补齐后续新增的列
 */
public final class FarmPlotSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_FARM_PLOT = "farm_plot";
    public static final String COL_UUID = "uuid";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_BLUEPRINT_NAME = "blueprint_name";
    public static final String COL_NAME = "name";
    public static final String COL_SLOTS = "slots";
    public static final String COL_CROP_ID = "crop_id";
    public static final String COL_WORKER_IDS = "worker_ids";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_MIN_X = "min_x";
    public static final String COL_MIN_Y = "min_y";
    public static final String COL_MIN_Z = "min_z";
    public static final String COL_MAX_X = "max_x";
    public static final String COL_MAX_Y = "max_y";
    public static final String COL_MAX_Z = "max_z";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_EXTRA_REGIONS = "extra_regions";

    @Override
    public int version() {
        /* 版本号只用来给迁移排序、以及记进 PRAGMA user_version 供排查；
           Database.migrate 现在**每次都执行全部迁移**（apply 都是幂等的），
           所以这里给多少都不会导致"表建不出来"。
           当前占用：Npc 5 / Building 7 / FarmLand 9 / Ranch 10 / FarmPlot 11 / Nation 12
           / Territory 13 / PlayerProfile 15。 */
        return 11;
    }

    @Override
    public String tableName() {
        return TABLE_FARM_PLOT;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_FARM_PLOT + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_FARM_PLOT + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_FARM_PLOT + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_FARM_PLOT + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_FARM_PLOT + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_BUILDING_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_BLUEPRINT_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_SLOTS, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CROP_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_WORKER_IDS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MIN_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_EXTRA_REGIONS, "TEXT NOT NULL DEFAULT ''");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_BUILDING_ID, COL_BLUEPRINT_NAME, COL_NAME, COL_SLOTS, COL_CROP_ID,
                COL_WORKER_IDS, COL_DIMENSION, COL_MIN_X, COL_MIN_Y, COL_MIN_Z,
                COL_MAX_X, COL_MAX_Y, COL_MAX_Z, COL_CREATED_DAY, COL_UPDATED_AT, COL_EXTRA_REGIONS
        });
    }
}
