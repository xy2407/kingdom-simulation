package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 游客表迁移：每个游客一条记录，存「还能活多少 tick」。
 * 寿命只在游客自己的 tick 里递减，实体不在加载区块就停表；数据库只做备份，不做绝对时间判断。
 */
public final class TouristSchema implements Migration {

    public static final String TABLE_TOURIST = "tourist";
    public static final String COL_NPC_ID = "npc_id";
    public static final String COL_REMAINING = "remaining_ticks";
    public static final String COL_UPDATED_AT = "updated_at";

    public static final String COLUMNS = COL_NPC_ID + ", " + COL_REMAINING + ", " + COL_UPDATED_AT;

    private static final int VERSION = 41;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_TOURIST;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_NPC_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            // 旧表存的是绝对时间，尚未上线，直接重建为剩余 tick 版本
            st.executeUpdate("DROP TABLE IF EXISTS " + TABLE_TOURIST);
            st.executeUpdate("CREATE TABLE " + TABLE_TOURIST + " ("
                    + COL_NPC_ID + " TEXT PRIMARY KEY, "
                    + COL_REMAINING + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }
}
