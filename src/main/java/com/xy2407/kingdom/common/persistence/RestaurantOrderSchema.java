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
 * 餐厅订单表迁移：一名顾客一行（顾客 UUID 作主键）。
 * 只存"当前未完成的订单"；重启后按需求直接丢弃，等顾客饥饿轮询重新下单。
 */
public final class RestaurantOrderSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_RESTAURANT_ORDER = "restaurant_order";
    public static final String COL_CUSTOMER_ID = "customer_id";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_SEAT_X = "seat_x";
    public static final String COL_SEAT_Y = "seat_y";
    public static final String COL_SEAT_Z = "seat_z";
    public static final String COL_DISH_ID = "dish_id";
    public static final String COL_STATUS = "status";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 19;
    }

    @Override
    public String tableName() {
        return TABLE_RESTAURANT_ORDER;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_CUSTOMER_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_RESTAURANT_ORDER + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_RESTAURANT_ORDER + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_RESTAURANT_ORDER + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_CUSTOMER_ID)) {
            throw new SQLException(TABLE_RESTAURANT_ORDER + " 表缺少 " + COL_CUSTOMER_ID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_RESTAURANT_ORDER + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_CUSTOMER_ID, "TEXT PRIMARY KEY");
        defs.put(COL_BUILDING_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_SEAT_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SEAT_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SEAT_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DISH_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_STATUS, "TEXT NOT NULL DEFAULT 'PENDING'");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_CUSTOMER_ID, COL_BUILDING_ID, COL_DIMENSION,
                COL_SEAT_X, COL_SEAT_Y, COL_SEAT_Z, COL_DISH_ID, COL_STATUS, COL_UPDATED_AT
        });
    }
}
