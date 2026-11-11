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
 * 作业范围表迁移：伐木范围与养殖范围共用一张 plot_area 表（按 kind 区分）。
 */
public final class PlotAreaSchema implements Migration {

    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    private static final int VERSION = 39;

    public static final String TABLE_PLOT_AREA = "plot_area";
    public static final String COL_UUID = "uuid";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_KIND = "kind";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_CELLS = "cells";
    public static final String COL_WORKERS = "worker_ids";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_PLOT_AREA;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_PLOT_AREA + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_PLOT_AREA + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_PLOT_AREA + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_PLOT_AREA + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_PLOT_AREA + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_BUILDING_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_KIND, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CELLS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_WORKERS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_BUILDING_ID, COL_KIND, COL_DIMENSION, COL_CELLS, COL_WORKERS,
                COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
