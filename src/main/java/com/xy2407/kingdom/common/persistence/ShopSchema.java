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
 * 商铺表迁移：一建筑一行，存营业开关与开业时扫出的货架坐标串
 */
public final class ShopSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_SHOP = "shop";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_IS_OPEN = "is_open";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_SHELVES = "shelves";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 27;
    }

    @Override
    public String tableName() {
        return TABLE_SHOP;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_BUILDING_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_SHOP + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_SHOP + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_SHOP + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_BUILDING_ID)) {
            throw new SQLException(TABLE_SHOP + " 表缺少 " + COL_BUILDING_ID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_SHOP + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_BUILDING_ID, "TEXT PRIMARY KEY");
        defs.put(COL_IS_OPEN, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_SHELVES, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_BUILDING_ID, COL_IS_OPEN, COL_DIMENSION, COL_SHELVES, COL_UPDATED_AT
        });
    }
}
