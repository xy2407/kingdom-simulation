package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 游客表迁移：每位游客一条记录，含各自的寿命截止时刻（世界 gameTime）。
 * 截止一到，游客本体与这条记录都会被清除。
 */
public final class TouristSchema implements Migration {

    public static final String TABLE_TOURIST = "tourist";
    public static final String COL_NPC_ID = "npc_id";
    public static final String COL_EXPIRE_AT = "expire_at";
    public static final String COL_UPDATED_AT = "updated_at";

    public static final String COLUMNS = COL_NPC_ID + ", " + COL_EXPIRE_AT + ", " + COL_UPDATED_AT;

    private static final int VERSION = 39;

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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_TOURIST + " ("
                    + COL_NPC_ID + " TEXT PRIMARY KEY, "
                    + COL_EXPIRE_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }
}
