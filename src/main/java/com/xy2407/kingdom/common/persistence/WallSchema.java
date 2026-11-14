package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 城墙表迁移：节点 wall_node、墙段 wall_segment、成品方块 wall_blocks 三张表
 */
public final class WallSchema implements Migration {

    public static final String TABLE_NODE = "wall_node";
    public static final String TABLE_SEGMENT = "wall_segment";
    public static final String TABLE_BLOCKS = "wall_blocks";
    public static final String TABLE_CHUNK = "wall_chunk";

    public static final String COL_NODE_KEY = "node_key";
    public static final String COL_SEGMENT_KEY = "segment_key";
    public static final String COL_TASK_KEY = "task_key";
    public static final String COL_CHUNK_KEY = "chunk_key";
    public static final String COL_CHUNK_X = "chunk_x";
    public static final String COL_CHUNK_Z = "chunk_z";
    public static final String COL_CELLS = "cells";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_X = "x";
    public static final String COL_Y = "y";
    public static final String COL_Z = "z";
    public static final String COL_AX = "ax";
    public static final String COL_AY = "ay";
    public static final String COL_AZ = "az";
    public static final String COL_BX = "bx";
    public static final String COL_BY = "by";
    public static final String COL_BZ = "bz";
    public static final String COL_KIND = "kind";
    public static final String COL_ROTATION = "rotation";
    public static final String COL_STATUS = "status";
    public static final String COL_PROGRESS = "progress";
    public static final String COL_TOTAL = "total";
    public static final String COL_TERRITORY = "territory";
    public static final String COL_OWNER = "owner";
    public static final String COL_BLOCKS = "blocks";
    public static final String COL_UPDATED_AT = "updated_at";

    private static final int VERSION = 42;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String tableName() {
        return TABLE_NODE;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_NODE_KEY;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_NODE + " ("
                    + COL_NODE_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_Y + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_KIND + " TEXT NOT NULL DEFAULT 'TOWER', "
                    + COL_ROTATION + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STATUS + " TEXT NOT NULL DEFAULT 'PLANNED', "
                    + COL_PROGRESS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_TOTAL + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_TERRITORY + " TEXT NOT NULL DEFAULT '', "
                    + COL_OWNER + " TEXT NOT NULL DEFAULT '', "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_SEGMENT + " ("
                    + COL_SEGMENT_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_AX + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_AY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_AZ + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_BX + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_BY + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_BZ + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_STATUS + " TEXT NOT NULL DEFAULT 'PLANNED', "
                    + COL_PROGRESS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_TOTAL + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_BLOCKS + " ("
                    + COL_TASK_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_BLOCKS + " BLOB, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_CHUNK + " ("
                    + COL_CHUNK_KEY + " TEXT PRIMARY KEY, "
                    + COL_DIMENSION + " TEXT NOT NULL DEFAULT '', "
                    + COL_CHUNK_X + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CHUNK_Z + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CELLS + " BLOB, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static String columnsNode() {
        return String.join(", ", new String[] {
                COL_NODE_KEY, COL_DIMENSION, COL_X, COL_Y, COL_Z, COL_KIND, COL_ROTATION,
                COL_STATUS, COL_PROGRESS, COL_TOTAL, COL_TERRITORY, COL_OWNER, COL_UPDATED_AT
        });
    }

    public static String columnsSegment() {
        return String.join(", ", new String[] {
                COL_SEGMENT_KEY, COL_DIMENSION, COL_AX, COL_AY, COL_AZ,
                COL_BX, COL_BY, COL_BZ, COL_STATUS, COL_PROGRESS, COL_TOTAL, COL_UPDATED_AT
        });
    }

    public static String columnsBlocks() {
        return String.join(", ", new String[] {
                COL_TASK_KEY, COL_DIMENSION, COL_BLOCKS, COL_UPDATED_AT
        });
    }

    public static String columnsChunk() {
        return String.join(", ", new String[] {
                COL_CHUNK_KEY, COL_DIMENSION, COL_CHUNK_X, COL_CHUNK_Z, COL_CELLS, COL_UPDATED_AT
        });
    }

    public static String chunkKey(String dimension, int chunkX, int chunkZ) {
        return dimension + "|" + chunkX + "|" + chunkZ;
    }

    public static String nodeKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    public static String segmentKey(String dimension, String keyA, String keyB) {
        return keyA.compareTo(keyB) <= 0
                ? dimension + "|" + keyA + "|" + keyB
                : dimension + "|" + keyB + "|" + keyA;
    }
}
