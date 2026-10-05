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
 * 国家表迁移：建 nation 表、幂等补列。
 *
 * <p>国家分两种，用 {@code permanent} 区分：</p>
 * <ul>
 *   <li><b>预置国</b>（瓦拉里安等）：{@code permanent=0}，{@code founder_uuid} 为空串。
 *       它们由代码内置，不落库也可以，落库只是为了统一查询。</li>
 *   <li><b>自建国</b>：{@code permanent=1}，记下 {@code founder_uuid}。
 *       <b>这类国家在该存档永久存在、不提供任何删除入口</b>——
 *       {@code NationRegistry} 里连删除方法都不放，避免以后有人顺手接一个上去。
 *       其它新玩家首登时能选到它。</li>
 * </ul>
 */
public final class NationSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_NATION = "nation";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_RULER = "ruler";
    public static final String COL_MOTTO = "motto";
    public static final String COL_COLOR_LEFT = "color_left";
    public static final String COL_COLOR_RIGHT = "color_right";
    public static final String COL_EMBLEM = "emblem";
    public static final String COL_EMBLEM_COLOR = "emblem_color";
    public static final String COL_FOUNDER_UUID = "founder_uuid";
    public static final String COL_PERMANENT = "permanent";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 12;
    }

    @Override
    public String tableName() {
        return TABLE_NATION;
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
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_NATION + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_NATION + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_NATION + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_NATION + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_ID, "TEXT PRIMARY KEY");
        defs.put(COL_NAME, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_RULER, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_MOTTO, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_COLOR_LEFT, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_COLOR_RIGHT, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_EMBLEM, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_EMBLEM_COLOR, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_FOUNDER_UUID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_PERMANENT, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ID, COL_NAME, COL_RULER, COL_MOTTO, COL_COLOR_LEFT, COL_COLOR_RIGHT,
                COL_EMBLEM, COL_EMBLEM_COLOR, COL_FOUNDER_UUID, COL_PERMANENT,
                COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
