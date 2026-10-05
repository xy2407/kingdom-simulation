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
 * 玩家档案表迁移：建 player_profile 表、幂等补列。
 *
 * <p>这张表管两件事：</p>
 * <ol>
 *   <li><b>首登标记</b>。{@code onboarded=0} 表示这名玩家还没走完"选国家"流程——
 *       登录时据此弹出阵营选择界面。走完就置 1，之后再登录不会再弹。</li>
 *   <li><b>归属</b>。{@code nation_id} 是首登时选定的国家（或自建的那个），
 *       {@code territory_id} 是自己那块领地。两者都为空串表示还没选／还没建。</li>
 * </ol>
 *
 * <p>用玩家 UUID 当主键，跟原版 playerdata 无关，换皮肤/改名都不受影响。</p>
 */
public final class PlayerProfileSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_PLAYER_PROFILE = "player_profile";
    public static final String COL_UUID = "uuid";
    public static final String COL_NATION_ID = "nation_id";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_ONBOARDED = "onboarded";
    public static final String COL_FIRST_JOIN_DAY = "first_join_day";
    /** 该玩家上次查看地图时停留的中心点。每人一份，与他人无关。 */
    public static final String COL_MAP_CENTER_X = "map_center_x";
    public static final String COL_MAP_CENTER_Z = "map_center_z";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        /* 版本号只用来排序与排查。Database.migrate 现在**每次启动都执行全部迁移**
           （apply 幂等：建表 IF NOT EXISTS + 查过列再 ALTER），
           所以新加的列（如 map_center_x / map_center_z）会自动补上，
           不再需要靠"抬高版本号"来触发。 */
        return 15;
    }

    @Override
    public String tableName() {
        return TABLE_PLAYER_PROFILE;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_PLAYER_PROFILE + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_PLAYER_PROFILE + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_PLAYER_PROFILE + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_PLAYER_PROFILE + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_NATION_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_ONBOARDED, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_FIRST_JOIN_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAP_CENTER_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAP_CENTER_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_NATION_ID, COL_TERRITORY_ID, COL_ONBOARDED,
                COL_FIRST_JOIN_DAY, COL_MAP_CENTER_X, COL_MAP_CENTER_Z, COL_UPDATED_AT
        });
    }
}
