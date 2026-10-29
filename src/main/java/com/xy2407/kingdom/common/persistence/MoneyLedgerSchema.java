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
 * 资金流水表迁移：一笔一条，按领地归属，供市政厅资金流水面板读取
 */
public final class MoneyLedgerSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_LEDGER = "money_ledger";
    public static final String COL_ENTRY_ID = "entry_id";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_KIND = "kind";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_BALANCE_AFTER = "balance_after";
    public static final String COL_STAMP = "stamp";

    @Override
    public int version() {
        return 29;
    }

    @Override
    public String tableName() {
        return TABLE_LEDGER;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_ENTRY_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_LEDGER + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_LEDGER + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_LEDGER + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_ENTRY_ID)) {
            throw new SQLException(TABLE_LEDGER + " 表缺少 " + COL_ENTRY_ID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_LEDGER + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_ENTRY_ID, "TEXT PRIMARY KEY");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_KIND, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_AMOUNT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_BALANCE_AFTER, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_STAMP, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ENTRY_ID, COL_TERRITORY_ID, COL_KIND, COL_AMOUNT, COL_BALANCE_AFTER, COL_STAMP
        });
    }
}
