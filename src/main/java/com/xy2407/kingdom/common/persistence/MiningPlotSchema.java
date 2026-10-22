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
 * 采矿场表迁移：建 mining_plot 表并幂等补齐后续新增的列
 */
public final class MiningPlotSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    private static final int VERSION = 23;

    public static final String TABLE_MINING_PLOT = "mining_plot";
    public static final String COL_UUID = "uuid";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_NAME = "name";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_MIN_X = "min_x";
    public static final String COL_MIN_Y = "min_y";
    public static final String COL_MIN_Z = "min_z";
    public static final String COL_MAX_X = "max_x";
    public static final String COL_MAX_Y = "max_y";
    public static final String COL_MAX_Z = "max_z";
    public static final String COL_SURFACE_Y = "surface_y";
    public static final String COL_CURRENT_Y = "current_y";
    public static final String COL_CURSOR = "cursor";
    public static final String COL_STATUS = "status";
    public static final String COL_WORKER_IDS = "worker_ids";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_CELLS = "cells";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_MINING_PLOT;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_MINING_PLOT + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_MINING_PLOT + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_MINING_PLOT + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_MINING_PLOT + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_MINING_PLOT + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_BUILDING_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MIN_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SURFACE_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CURRENT_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CURSOR, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_STATUS, "TEXT NOT NULL DEFAULT 'READY'");
        defs.put(COL_WORKER_IDS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CELLS, "TEXT NOT NULL DEFAULT ''");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_BUILDING_ID, COL_NAME, COL_DIMENSION, COL_MIN_X, COL_MIN_Y,
                COL_MIN_Z, COL_MAX_X, COL_MAX_Y, COL_MAX_Z, COL_SURFACE_Y, COL_CURRENT_Y,
                COL_CURSOR, COL_STATUS, COL_WORKER_IDS, COL_CREATED_DAY, COL_UPDATED_AT, COL_CELLS
        });
    }
}
