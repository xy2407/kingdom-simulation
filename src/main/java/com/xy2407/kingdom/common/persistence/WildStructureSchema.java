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
 * 野生结构表迁移：一个结构实例一行，记录它完整算出的区块范围与已落盘的野生领地
 */
public final class WildStructureSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_WILD = "wild_structure";
    public static final String COL_INSTANCE_KEY = "instance_key";
    public static final String COL_STRUCTURE_ID = "structure_id";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_NATION_ID = "nation_id";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_CHUNKS = "chunks";
    public static final String COL_MIN_CHUNK_X = "min_chunk_x";
    public static final String COL_MIN_CHUNK_Z = "min_chunk_z";
    public static final String COL_MAX_CHUNK_X = "max_chunk_x";
    public static final String COL_MAX_CHUNK_Z = "max_chunk_z";
    public static final String COL_SCANNED_AT = "scanned_at";

    @Override
    public int version() {
        return 35;
    }

    @Override
    public String tableName() {
        return TABLE_WILD;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_INSTANCE_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_WILD + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_WILD + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_WILD + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_WILD + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_INSTANCE_KEY, "TEXT PRIMARY KEY");
        defs.put(COL_STRUCTURE_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NATION_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_TERRITORY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CHUNKS, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MIN_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_SCANNED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[]{
                COL_INSTANCE_KEY, COL_STRUCTURE_ID, COL_DIMENSION, COL_NATION_ID,
                COL_TERRITORY_ID, COL_CHUNKS, COL_MIN_CHUNK_X, COL_MIN_CHUNK_Z,
                COL_MAX_CHUNK_X, COL_MAX_CHUNK_Z, COL_SCANNED_AT
        });
    }
}
