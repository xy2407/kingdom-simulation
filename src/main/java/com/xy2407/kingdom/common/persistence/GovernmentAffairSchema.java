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
 * 政务表迁移：一条政务一行，随领地级联清理并幂等补齐新增列
 */
public final class GovernmentAffairSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_AFFAIR = "government_affair";
    public static final String COL_UUID = "uuid";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_TYPE = "type";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_EXPIRE_DAY = "expire_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 20;
    }

    @Override
    public String tableName() {
        return TABLE_AFFAIR;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_AFFAIR + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_AFFAIR + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_AFFAIR + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_AFFAIR + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_AFFAIR + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TYPE, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_EXPIRE_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_TERRITORY_ID, COL_TYPE, COL_CREATED_DAY, COL_EXPIRE_DAY, COL_UPDATED_AT
        });
    }
}
