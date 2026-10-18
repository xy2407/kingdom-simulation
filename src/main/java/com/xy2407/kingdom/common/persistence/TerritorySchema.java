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
 * 领地表迁移：领地隶属国家，范围以区块集合记，旧存档按矩形回填
 */
public final class TerritorySchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_TERRITORY = "territory";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_NATION_ID = "nation_id";
    public static final String COL_OWNER_UUID = "owner_uuid";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_CENTER_X = "center_x";
    public static final String COL_CENTER_Y = "center_y";
    public static final String COL_CENTER_Z = "center_z";
    public static final String COL_MIN_CHUNK_X = "min_chunk_x";
    public static final String COL_MIN_CHUNK_Z = "min_chunk_z";
    public static final String COL_MAX_CHUNK_X = "max_chunk_x";
    public static final String COL_MAX_CHUNK_Z = "max_chunk_z";
    public static final String COL_CHUNKS = "chunks";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 14;
    }

    @Override
    public String tableName() {
        return TABLE_TERRITORY;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_TERRITORY + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_TERRITORY + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_TERRITORY + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_TERRITORY + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_ID, "TEXT PRIMARY KEY");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NATION_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_OWNER_UUID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CENTER_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CENTER_Y, "INTEGER NOT NULL DEFAULT 64");
        defs.put(COL_CENTER_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CHUNKS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ID, COL_NAME, COL_NATION_ID, COL_OWNER_UUID, COL_DIMENSION,
                COL_CENTER_X, COL_CENTER_Y, COL_CENTER_Z, COL_MIN_CHUNK_X, COL_MIN_CHUNK_Z,
                COL_MAX_CHUNK_X, COL_MAX_CHUNK_Z, COL_CHUNKS, COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
