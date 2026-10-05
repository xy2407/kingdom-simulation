package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 开垦记录表迁移：NPC 耕过的地表坐标，供"永久湿润"的 mixin 查表
 */
public final class FarmLandSchema implements Migration {

    public static final String TABLE_FARM_LAND = "farm_land";
    public static final String COL_ID = "id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_X = "x";
    public static final String COL_Y = "y";
    public static final String COL_Z = "z";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    public static final String COLUMNS = COL_ID + ", " + COL_DIMENSION + ", " + COL_X + ", " + COL_Y + ", "
            + COL_Z + ", " + COL_CREATED_DAY + ", " + COL_UPDATED_AT;

    @Override
    public int version() {
        return 9;
    }

    @Override
    public String tableName() {
        return TABLE_FARM_LAND;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_FARM_LAND + " ("
                    + COL_ID + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL, "
                    + COL_X + " INTEGER NOT NULL, "
                    + COL_Y + " INTEGER NOT NULL, "
                    + COL_Z + " INTEGER NOT NULL, "
                    + COL_CREATED_DAY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    /** 坐标唯一键，同一格重复开垦只会 UPSERT 自己。 */
    public static String landId(String dimension, int x, int y, int z) {
        return dimension + "@" + x + "," + y + "," + z;
    }
}
