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
 * 玩家启动资金发放表迁移：一人一行，防止重复领取那 50 枚启动资金
 */
public final class PlayerGrantSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_GRANT = "player_starter_grant";
    public static final String COL_UUID = "uuid";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_GRANTED_DAY = "granted_day";
    public static final String COL_GRANTED_AT = "granted_at";

    @Override
    public int version() {
        return 30;
    }

    @Override
    public String tableName() {
        return TABLE_GRANT;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_GRANT + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_GRANT + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_GRANT + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_GRANT + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_GRANT + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_AMOUNT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_GRANTED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_GRANTED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_AMOUNT, COL_GRANTED_DAY, COL_GRANTED_AT
        });
    }
}
