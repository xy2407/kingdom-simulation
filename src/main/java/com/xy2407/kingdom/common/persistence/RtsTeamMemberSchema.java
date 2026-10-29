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
 * RTS 队伍成员表迁移：一名成员一行，记所属队伍与他相对队长的水平偏移
 */
public final class RtsTeamMemberSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_MEMBER = "rts_team_member";
    public static final String COL_KEY = "member_key";
    public static final String COL_TEAM_ID = "team_id";
    public static final String COL_MEMBER_UUID = "member_uuid";
    public static final String COL_OFFSET_X = "offset_x";
    public static final String COL_OFFSET_Z = "offset_z";

    @Override
    public int version() {
        return 33;
    }

    @Override
    public String tableName() {
        return TABLE_MEMBER;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_MEMBER + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_MEMBER + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_MEMBER + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_MEMBER + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_KEY, "TEXT PRIMARY KEY");
        defs.put(COL_TEAM_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MEMBER_UUID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_OFFSET_X, "REAL NOT NULL DEFAULT 0");
        defs.put(COL_OFFSET_Z, "REAL NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_KEY, COL_TEAM_ID, COL_MEMBER_UUID, COL_OFFSET_X, COL_OFFSET_Z
        });
    }

    public static String keyOf(String teamId, String memberId) {
        return teamId + "@" + memberId;
    }
}
