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
 * 餐厅座位表迁移：开业扫描出的椅子与紧邻桌子，按建筑加座位坐标一行一条
 */
public final class RestaurantSeatSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_RESTAURANT_SEAT = "restaurant_seat";
    public static final String COL_SEAT_KEY = "seat_key";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_SEAT_X = "seat_x";
    public static final String COL_SEAT_Y = "seat_y";
    public static final String COL_SEAT_Z = "seat_z";
    public static final String COL_HAS_TABLE = "has_table";
    public static final String COL_TABLE_X = "table_x";
    public static final String COL_TABLE_Y = "table_y";
    public static final String COL_TABLE_Z = "table_z";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 31;
    }

    @Override
    public String tableName() {
        return TABLE_RESTAURANT_SEAT;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_SEAT_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_RESTAURANT_SEAT + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_RESTAURANT_SEAT + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_RESTAURANT_SEAT + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_SEAT_KEY)) {
            throw new SQLException(TABLE_RESTAURANT_SEAT + " 表缺少 " + COL_SEAT_KEY + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_RESTAURANT_SEAT + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_SEAT_KEY, "TEXT PRIMARY KEY");
        defs.put(COL_BUILDING_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_SEAT_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SEAT_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SEAT_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_HAS_TABLE, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_TABLE_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_TABLE_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_TABLE_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_SEAT_KEY, COL_BUILDING_ID, COL_DIMENSION,
                COL_SEAT_X, COL_SEAT_Y, COL_SEAT_Z,
                COL_HAS_TABLE, COL_TABLE_X, COL_TABLE_Y, COL_TABLE_Z, COL_UPDATED_AT
        });
    }
}
