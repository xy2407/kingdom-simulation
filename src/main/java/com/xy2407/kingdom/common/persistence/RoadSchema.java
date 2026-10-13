package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 道路表迁移：领地道路按格存 road_cell，世界道路按区块聚合存 road_chunk。
 */
public final class RoadSchema implements Migration {

    public static final String TABLE_CELL = "road_cell";
    public static final String TABLE_CHUNK = "road_chunk";

    public static final String COL_CELL_KEY = "cell_key";
    public static final String COL_CHUNK_KEY = "chunk_key";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_X = "x";
    public static final String COL_Y = "y";
    public static final String COL_Z = "z";
    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_OWNER = "owner";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_CHUNK_X = "chunk_x";
    public static final String COL_CHUNK_Z = "chunk_z";
    public static final String COL_CELLS = "cells";

    private static final int VERSION = 21;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_CELL;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_CELL_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_CELL + " ("
                    + COL_CELL_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_TERRITORY_ID + " TEXT NOT NULL DEFAULT '', "
                    + COL_OWNER + " TEXT NOT NULL DEFAULT '', "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_CHUNK + " ("
                    + COL_CHUNK_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_CHUNK_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CHUNK_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CELLS + " BLOB, "
                    + COL_OWNER + " TEXT NOT NULL DEFAULT '', "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String columnsCell() {
        return String.join(", ", new String[] {
                COL_CELL_KEY, COL_DIMENSION, COL_X, COL_Y, COL_Z,
                COL_TERRITORY_ID, COL_OWNER, COL_UPDATED_AT
        });
    }

    public static String columnsChunk() {
        return String.join(", ", new String[] {
                COL_CHUNK_KEY, COL_DIMENSION, COL_CHUNK_X, COL_CHUNK_Z,
                COL_CELLS, COL_OWNER, COL_UPDATED_AT
        });
    }

    public static String cellKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    public static String chunkKey(String dimension, int chunkX, int chunkZ) {
        return dimension + "|" + chunkX + "|" + chunkZ;
    }
}
