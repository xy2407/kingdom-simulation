package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 养殖栏表迁移：一栏一条记录，含饲养员、运行开关与状态
 */
public final class RanchSchema implements Migration {

    public static final String TABLE_RANCH_PEN = "ranch_pen";
    public static final String COL_UUID = "uuid";
    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_BLUEPRINT_NAME = "blueprint_name";
    public static final String COL_ANIMAL_ID = "animal_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_MIN_X = "min_x";
    public static final String COL_MIN_Y = "min_y";
    public static final String COL_MIN_Z = "min_z";
    public static final String COL_MAX_X = "max_x";
    public static final String COL_MAX_Y = "max_y";
    public static final String COL_MAX_Z = "max_z";
    public static final String COL_KEEPER_IDS = "keeper_ids";
    public static final String COL_RUNNING = "running";
    public static final String COL_STATUS_KEY = "status_key";
    public static final String COL_UPDATED_AT = "updated_at";

    public static final String COLUMNS = COL_UUID + ", " + COL_BUILDING_ID + ", " + COL_BLUEPRINT_NAME + ", "
            + COL_ANIMAL_ID + ", " + COL_DIMENSION + ", " + COL_MIN_X + ", " + COL_MIN_Y + ", " + COL_MIN_Z + ", "
            + COL_MAX_X + ", " + COL_MAX_Y + ", " + COL_MAX_Z + ", " + COL_KEEPER_IDS + ", " + COL_RUNNING + ", "
            + COL_STATUS_KEY + ", " + COL_UPDATED_AT;

    @Override
    public int version() {
        return 10;
    }

    @Override
    public String tableName() {
        return TABLE_RANCH_PEN;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_UUID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_RANCH_PEN + " ("
                    + COL_UUID + " TEXT PRIMARY KEY, "
                    + COL_BUILDING_ID + " TEXT NOT NULL, "
                    + COL_BLUEPRINT_NAME + " TEXT NOT NULL DEFAULT '', "
                    + COL_ANIMAL_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_MIN_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MIN_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MIN_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_KEEPER_IDS + " TEXT NOT NULL DEFAULT '', "
                    + COL_RUNNING + " INTEGER NOT NULL DEFAULT 1, "
                    + COL_STATUS_KEY + " TEXT NOT NULL DEFAULT '', "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String penKey(Object buildingId, String animalId) {
        return buildingId + "#" + (animalId == null ? "" : animalId);
    }
}
