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
 * 建筑施工任务表迁移：蓝图、落点、进度游标与建筑工都落库，重启后按块恢复
 */
public final class BuildingTaskSchema implements Migration {

    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    private static final int VERSION = 26;

    public static final String TABLE_BUILDING_TASK = "building_task";
    public static final String COL_ID = "id";
    public static final String COL_BLUEPRINT = "blueprint";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_ORIGIN_X = "origin_x";
    public static final String COL_ORIGIN_Y = "origin_y";
    public static final String COL_ORIGIN_Z = "origin_z";
    public static final String COL_ROTATION = "rotation";
    public static final String COL_LEVEL = "level";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_CURSOR = "cursor";
    public static final String COL_TOTAL = "total";
    public static final String COL_ARCHITECT_ID = "architect_id";
    public static final String COL_ARCHITECT_NAME = "architect_name";
    public static final String COL_PAUSED = "paused";
    public static final String COL_DEPOSIT_X = "deposit_x";
    public static final String COL_DEPOSIT_Y = "deposit_y";
    public static final String COL_DEPOSIT_Z = "deposit_z";
    public static final String COL_MATERIALS = "materials";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_BUILDING_TASK;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS "
                + TABLE_BUILDING_TASK + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_BUILDING_TASK + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_BUILDING_TASK + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_ID)) {
            throw new SQLException(TABLE_BUILDING_TASK + " 表缺少 " + COL_ID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_BUILDING_TASK + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_ID, "TEXT PRIMARY KEY");
        defs.put(COL_BLUEPRINT, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_ORIGIN_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_ORIGIN_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_ORIGIN_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_ROTATION, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_LEVEL, "INTEGER NOT NULL DEFAULT 1");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CURSOR, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_TOTAL, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_ARCHITECT_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_ARCHITECT_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_PAUSED, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DEPOSIT_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DEPOSIT_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DEPOSIT_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MATERIALS, "TEXT NOT NULL DEFAULT '{}'");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ID, COL_BLUEPRINT, COL_DIMENSION, COL_ORIGIN_X, COL_ORIGIN_Y, COL_ORIGIN_Z,
                COL_ROTATION, COL_LEVEL, COL_CREATED_DAY, COL_CURSOR, COL_TOTAL,
                COL_ARCHITECT_ID, COL_ARCHITECT_NAME, COL_PAUSED,
                COL_DEPOSIT_X, COL_DEPOSIT_Y, COL_DEPOSIT_Z, COL_MATERIALS, COL_UPDATED_AT
        });
    }
}
