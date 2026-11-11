package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 锻造任务表迁移：一座锻造坊一行，存目标产物、展开后的步骤计划与各步骤进度
 */
public final class ForgeTaskSchema implements Migration {

    public static final String TABLE_FORGE_TASK = "forge_task";

    public static final String COL_BUILDING_ID = "building_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_TARGET = "target";
    public static final String COL_REQUESTED = "requested";
    public static final String COL_STATE = "state";
    public static final String COL_PLAN = "plan";
    public static final String COL_PROGRESS = "progress";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    private static final int VERSION = 39;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_FORGE_TASK;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_BUILDING_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_FORGE_TASK + " ("
                    + COL_BUILDING_ID + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_TARGET + " TEXT NOT NULL DEFAULT '', "
                    + COL_REQUESTED + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STATE + " TEXT NOT NULL DEFAULT 'PREPARING', "
                    + COL_PLAN + " TEXT NOT NULL DEFAULT '', "
                    + COL_PROGRESS + " TEXT NOT NULL DEFAULT '', "
                    + COL_CREATED_DAY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_BUILDING_ID, COL_DIMENSION, COL_TARGET, COL_REQUESTED,
                COL_STATE, COL_PLAN, COL_PROGRESS, COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
