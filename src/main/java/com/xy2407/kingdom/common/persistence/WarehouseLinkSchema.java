package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 仓库归属表迁移：一个建筑只归一个仓库，主键即建筑 id。
 * 地图架方块只是打开界面的入口，归属关系一律以这张表为准。
 */
public final class WarehouseLinkSchema implements Migration {

    public static final String TABLE_WAREHOUSE_LINK = "warehouse_link";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_WAREHOUSE_ID = "warehouse_id";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    public static final String COLUMNS = COL_BUILDING_ID + ", " + COL_WAREHOUSE_ID + ", "
            + COL_CREATED_AT + ", " + COL_UPDATED_AT;

    private static final int VERSION = 40;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_WAREHOUSE_LINK;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_BUILDING_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_WAREHOUSE_LINK + " ("
                    + COL_BUILDING_ID + " TEXT PRIMARY KEY, "
                    + COL_WAREHOUSE_ID + " TEXT NOT NULL, "
                    + COL_CREATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }
}
