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
 * RTS 队伍表迁移：一支队伍一行，记队长、归属玩家与城市、阵型是否已保存
 */
public final class RtsTeamSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_TEAM = "rts_team";
    public static final String COL_TEAM_ID = "team_id";
    public static final String COL_LEADER_UUID = "leader_uuid";
    public static final String COL_OWNER_UUID = "owner_uuid";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_FORMATION_SAVED = "formation_saved";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_PATROL_NODES = "patrol_nodes";
    public static final String COL_PATROL_INDEX = "patrol_index";
    public static final String COL_PATROL_FORWARD = "patrol_forward";
    public static final String COL_PATROL_ACTIVE = "patrol_active";

    @Override
    public int version() {
        return 37;
    }

    @Override
    public String tableName() {
        return TABLE_TEAM;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_TEAM_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_TEAM + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_TEAM + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_TEAM + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_TEAM + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_TEAM_ID, "TEXT PRIMARY KEY");
        defs.put(COL_LEADER_UUID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_OWNER_UUID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_FORMATION_SAVED, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CREATED_AT, "INTEGER NOT NULL DEFAULT 0");
        // 巡逻是队伍的常驻任务：路线序列化成 "x,y,z;x,y,z;..."，加下标与折返方向
        defs.put(COL_PATROL_NODES, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_PATROL_INDEX, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_PATROL_FORWARD, "INTEGER NOT NULL DEFAULT 1");
        defs.put(COL_PATROL_ACTIVE, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_TEAM_ID, COL_LEADER_UUID, COL_OWNER_UUID, COL_TERRITORY_ID,
                COL_FORMATION_SAVED, COL_CREATED_AT,
                COL_PATROL_NODES, COL_PATROL_INDEX, COL_PATROL_FORWARD, COL_PATROL_ACTIVE
        });
    }
}
