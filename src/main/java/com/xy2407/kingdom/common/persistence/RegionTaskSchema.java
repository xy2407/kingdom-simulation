package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 区域任务表迁移：region_task 存拆除填充任务，builder_employee 存建筑工雇员
 */
public final class RegionTaskSchema implements Migration {

    public static final String TABLE_REGION_TASK = "region_task";
    public static final String TABLE_EMPLOYEE = "builder_employee";

    public static final String COL_UUID = "uuid";
    public static final String COL_KIND = "kind";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_MIN_X = "min_x";
    public static final String COL_MIN_Y = "min_y";
    public static final String COL_MIN_Z = "min_z";
    public static final String COL_MAX_X = "max_x";
    public static final String COL_MAX_Y = "max_y";
    public static final String COL_MAX_Z = "max_z";
    public static final String COL_STATUS = "status";
    public static final String COL_CURSOR = "cursor";
    public static final String COL_TOTAL = "total";
    public static final String COL_FILL_ITEM = "fill_item";
    public static final String COL_ASSIGNED = "assigned";
    public static final String COL_MATERIALS = "materials";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_NPC_ID = "npc_id";
    public static final String COL_NAME = "name";
    public static final String COL_SKIN = "skin";
    public static final String COL_HIRED_DAY = "hired_day";

    private static final int VERSION = 22;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_REGION_TASK;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_UUID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_REGION_TASK + " ("
                    + COL_UUID + " TEXT PRIMARY KEY, "
                    + COL_KIND + " TEXT NOT NULL DEFAULT 'FILL', "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_MIN_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MIN_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MIN_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_MAX_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STATUS + " TEXT NOT NULL DEFAULT 'READY', "
                    + COL_CURSOR + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_TOTAL + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_FILL_ITEM + " TEXT NOT NULL DEFAULT 'minecraft:dirt', "
                    + COL_ASSIGNED + " TEXT NOT NULL DEFAULT '[]', "
                    + COL_MATERIALS + " TEXT NOT NULL DEFAULT '[]', "
                    + COL_CREATED_DAY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_EMPLOYEE + " ("
                    + COL_NPC_ID + " TEXT PRIMARY KEY, "
                    + COL_NAME + " TEXT NOT NULL DEFAULT '', "
                    + COL_SKIN + " TEXT NOT NULL DEFAULT '', "
                    + COL_HIRED_DAY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String columnsTask() {
        return String.join(", ", new String[] {
                COL_UUID, COL_KIND, COL_DIMENSION,
                COL_MIN_X, COL_MIN_Y, COL_MIN_Z, COL_MAX_X, COL_MAX_Y, COL_MAX_Z,
                COL_STATUS, COL_CURSOR, COL_TOTAL, COL_FILL_ITEM,
                COL_ASSIGNED, COL_MATERIALS, COL_CREATED_DAY, COL_UPDATED_AT
        });
    }

    public static String columnsEmployee() {
        return String.join(", ", new String[] {
                COL_NPC_ID, COL_NAME, COL_SKIN, COL_HIRED_DAY, COL_UPDATED_AT
        });
    }
}
