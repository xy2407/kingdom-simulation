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
 * NPC表结构迁移，建表并幂等补齐后续新增的列
 */
public final class NpcSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_NPC = "npc";
    public static final String COL_UUID = "uuid";
    public static final String COL_ENTITY_UUID = "entity_uuid";
    public static final String COL_NAME = "name";
    public static final String COL_NATION = "nation";
    public static final String COL_GENDER = "gender";
    public static final String COL_FAMILY_ID = "family_id";
    public static final String COL_FATHER_ID = "father_id";
    public static final String COL_MOTHER_ID = "mother_id";
    public static final String COL_SPOUSE_ID = "spouse_id";
    public static final String COL_JOB = "job";
    public static final String COL_WORKPLACE_ID = "workplace_id";
    public static final String COL_HOME_ID = "home_id";
    public static final String COL_AGE = "age";
    public static final String COL_LIFECYCLE = "lifecycle";
    public static final String COL_HOBBIES = "hobbies";
    public static final String COL_LIKED_ITEMS = "liked_items";
    public static final String COL_PERSONALITY = "personality";
    public static final String COL_STRENGTH = "strength";
    public static final String COL_INTELLIGENCE = "intelligence";
    public static final String COL_AGILITY = "agility";
    public static final String COL_HEALTH = "health";
    public static final String COL_MOOD = "mood";
    public static final String COL_HUNGER = "hunger";
    public static final String COL_STAMINA = "stamina";
    public static final String COL_WEALTH = "wealth";
    public static final String COL_DEAD = "dead";
    public static final String COL_BIRTH_DAY = "birth_day";
    public static final String COL_DEATH_DAY = "death_day";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_DIMENSION = "dimension";
    public static final String COL_POS_X = "pos_x";
    public static final String COL_POS_Y = "pos_y";
    public static final String COL_POS_Z = "pos_z";
    public static final String COL_HOME_X = "home_x";
    public static final String COL_HOME_Y = "home_y";
    public static final String COL_HOME_Z = "home_z";
    public static final String COL_WORK_X = "work_x";
    public static final String COL_WORK_Y = "work_y";
    public static final String COL_WORK_Z = "work_z";
    public static final String COL_STATE = "state";
    public static final String COL_SUB_STATE = "sub_state";
    public static final String COL_DETAIL = "detail";
    public static final String COL_SHIFT = "shift";
    /** 24 格行装（SNBT 文本）。 */
    public static final String COL_INVENTORY = "inventory";
    /** 皮肤名（textures/entity/npc 下的文件名）。 */
    public static final String COL_SKIN = "skin";

    @Override
    public int version() {
        return 5;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_NPC + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_NPC + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_NPC + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_UUID)) {
            throw new SQLException(TABLE_NPC + " 表缺少 " + COL_UUID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_NPC + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_UUID, "TEXT PRIMARY KEY");
        defs.put(COL_ENTITY_UUID, "TEXT");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_NATION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_GENDER, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_FAMILY_ID, "TEXT");
        defs.put(COL_FATHER_ID, "TEXT");
        defs.put(COL_MOTHER_ID, "TEXT");
        defs.put(COL_SPOUSE_ID, "TEXT");
        defs.put(COL_JOB, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_WORKPLACE_ID, "TEXT");
        defs.put(COL_HOME_ID, "TEXT");
        defs.put(COL_AGE, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_LIFECYCLE, "TEXT NOT NULL DEFAULT 'BORN'");
        defs.put(COL_HOBBIES, "TEXT NOT NULL DEFAULT '[]'");
        defs.put(COL_LIKED_ITEMS, "TEXT NOT NULL DEFAULT '[]'");
        defs.put(COL_PERSONALITY, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_STRENGTH, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_INTELLIGENCE, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_AGILITY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_HEALTH, "REAL NOT NULL DEFAULT 100");
        defs.put(COL_MOOD, "REAL NOT NULL DEFAULT 100");
        defs.put(COL_HUNGER, "REAL NOT NULL DEFAULT 20");
        defs.put(COL_STAMINA, "REAL NOT NULL DEFAULT 100");
        defs.put(COL_WEALTH, "BIGINT NOT NULL DEFAULT 0");
        defs.put(COL_DEAD, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_BIRTH_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DEATH_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_DIMENSION, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_POS_X, "REAL NOT NULL DEFAULT 0");
        defs.put(COL_POS_Y, "REAL NOT NULL DEFAULT 0");
        defs.put(COL_POS_Z, "REAL NOT NULL DEFAULT 0");
        defs.put(COL_HOME_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_HOME_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_HOME_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WORK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WORK_Y, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WORK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_STATE, "TEXT NOT NULL DEFAULT 'DAY'");
        defs.put(COL_SUB_STATE, "TEXT NOT NULL DEFAULT 'RESTING'");
        defs.put(COL_DETAIL, "TEXT NOT NULL DEFAULT 'WANDERING'");
        defs.put(COL_SHIFT, "TEXT NOT NULL DEFAULT 'DAY_SHIFT'");
        defs.put(COL_INVENTORY, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_SKIN, "TEXT NOT NULL DEFAULT ''");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_UUID, COL_ENTITY_UUID, COL_NAME, COL_NATION, COL_GENDER, COL_FAMILY_ID, COL_FATHER_ID,
                COL_MOTHER_ID, COL_SPOUSE_ID, COL_JOB, COL_WORKPLACE_ID, COL_HOME_ID, COL_AGE, COL_LIFECYCLE,
                COL_HOBBIES, COL_LIKED_ITEMS, COL_PERSONALITY, COL_STRENGTH, COL_INTELLIGENCE, COL_AGILITY,
                COL_HEALTH, COL_MOOD, COL_HUNGER, COL_STAMINA, COL_WEALTH, COL_DEAD, COL_BIRTH_DAY,
                COL_DEATH_DAY, COL_UPDATED_AT, COL_DIMENSION, COL_POS_X, COL_POS_Y, COL_POS_Z,
                COL_HOME_X, COL_HOME_Y, COL_HOME_Z, COL_WORK_X, COL_WORK_Y, COL_WORK_Z,
                COL_STATE, COL_SUB_STATE, COL_DETAIL, COL_SHIFT, COL_INVENTORY, COL_SKIN
        });
    }
}
