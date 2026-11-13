package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 货运订单表迁移：一单一行，跨重启保留未完成配送的状态与货物清单
 */
public final class FreightOrderSchema implements Migration {

    public static final String TABLE_FREIGHT_ORDER = "freight_order";

    public static final String COL_ID = "order_id";
    public static final String COL_TARGET = "target";
    public static final String COL_REQUESTER_ID = "requester_id";
    public static final String COL_DESTINATION = "destination";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_ITEMS = "items";
    public static final String COL_STATE = "state";
    public static final String COL_WAREHOUSE_ID = "warehouse_id";
    public static final String COL_PICKUP_ID = "pickup_id";
    public static final String COL_CARRIER_ID = "carrier_id";
    public static final String COL_NEXT_ATTEMPT = "next_attempt_at";
    public static final String COL_ATTEMPTS = "attempts";
    public static final String COL_STATE_AT = "state_at";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_REASON = "reason";
    public static final String COL_UPDATED_AT = "updated_at";

    private static final int VERSION = 40;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_FREIGHT_ORDER;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_FREIGHT_ORDER + " ("
                    + COL_ID + " TEXT PRIMARY KEY, "
                    + COL_TARGET + " TEXT NOT NULL DEFAULT 'BUILDING', "
                    + COL_REQUESTER_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_DESTINATION + " TEXT NOT NULL DEFAULT '', "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_ITEMS + " TEXT NOT NULL DEFAULT '', "
                    + COL_STATE + " TEXT NOT NULL DEFAULT 'PENDING', "
                    + COL_WAREHOUSE_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_PICKUP_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_CARRIER_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_NEXT_ATTEMPT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_ATTEMPTS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STATE_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CREATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_REASON + " TEXT NOT NULL DEFAULT '', "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ID, COL_TARGET, COL_REQUESTER_ID, COL_DESTINATION, COL_DIMENSION,
                COL_ITEMS, COL_STATE, COL_WAREHOUSE_ID, COL_PICKUP_ID, COL_CARRIER_ID,
                COL_NEXT_ATTEMPT, COL_ATTEMPTS, COL_STATE_AT, COL_CREATED_AT, COL_REASON,
                COL_UPDATED_AT
        });
    }
}
