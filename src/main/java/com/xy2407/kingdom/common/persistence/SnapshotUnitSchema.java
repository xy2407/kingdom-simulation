package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 单元快照磁盘表：磁盘只是缓存，世界方块才是权威。
 */
public final class SnapshotUnitSchema implements Migration {

    public static final String TABLE_SNAPSHOT = "snapshot";
    public static final String COL_KEY = "chunk_key";
    public static final String COL_DATA = "data";
    public static final String COL_FORMAT = "format_version";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 15;
    }

    @Override
    public String tableName() {
        return TABLE_SNAPSHOT;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_SNAPSHOT + " ("
                    + COL_KEY + " TEXT PRIMARY KEY, "
                    + COL_DATA + " BLOB, "
                    + COL_FORMAT + " INTEGER NOT NULL DEFAULT 1, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }
}
