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
 * 领地民心表：{@code territory_vitals}，主键就是领地 id。
 *
 * <p>存凝聚力 / 忠诚度 / 幸福度三个值，外加一份战争快照（敌方领地 id、开战日、
 * 开战瞬间双方人口、本方是否主动方）。开战快照要落库是因为凝聚力扣减
 * 以"开战人口"当分母，重进世界后分母不能丢。</p>
 *
 * <p><b>为什么单独一张表而不是给 {@code territory} 加列。</b>
 * 领地本身的字段（地界/归属/创建日）定下就不动，而这几个值天天在变；
 * 分开之后领地记录保持不可变，变动全落在这张表上，互不干扰。
 * 领地不存在时这里的行也不会碍事（主键是领地 id，不设外键）。</p>
 */
public final class TerritoryVitalsSchema implements Migration {

    public static final String TABLE_TERRITORY_VITALS = "territory_vitals";

    public static final String COL_TERRITORY_ID = "territory_id";
    public static final String COL_UNITY = "unity";
    public static final String COL_LOYALTY = "loyalty";
    public static final String COL_HAPPINESS = "happiness";
    public static final String COL_WAR_ENEMY_ID = "war_enemy_id";
    public static final String COL_WAR_START_DAY = "war_start_day";
    public static final String COL_WAR_SELF_POP = "war_self_pop";
    public static final String COL_WAR_ENEMY_POP = "war_enemy_pop";
    public static final String COL_WAR_AGGRESSOR = "war_aggressor";
    public static final String COL_UPDATED_AT = "updated_at";

    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    @Override
    public int version() {
        /* 版本号只用来排序与排查：Database.migrate 现在每次启动都执行全部迁移
           （apply 幂等），所以给多少都不会导致"表建不出来"。
           当前占用：… / Nation 12 / Territory 13 / PlayerProfile 15 / 本表 16。 */
        return 16;
    }

    @Override
    public String tableName() {
        return TABLE_TERRITORY_VITALS;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_TERRITORY_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_TERRITORY_VITALS + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_TERRITORY_VITALS + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_TERRITORY_VITALS + " 表不存在，无法补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_TERRITORY_VITALS + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_TERRITORY_ID, "TEXT PRIMARY KEY");
        /* 默认值与 TerritoryVitals 的常量一致：凝聚力 100、忠诚 80、幸福 80 */
        defs.put(COL_UNITY, "INTEGER NOT NULL DEFAULT 100");
        defs.put(COL_LOYALTY, "INTEGER NOT NULL DEFAULT 80");
        defs.put(COL_HAPPINESS, "INTEGER NOT NULL DEFAULT 80");
        defs.put(COL_WAR_ENEMY_ID, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_WAR_START_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WAR_SELF_POP, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WAR_ENEMY_POP, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_WAR_AGGRESSOR, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    /** 查表用的列清单（顺序与 {@code TerritoryVitalsStore} 的读取一致）。 */
    public static String columns() {
        return String.join(", ", new String[] {
                COL_TERRITORY_ID, COL_UNITY, COL_LOYALTY, COL_HAPPINESS,
                COL_WAR_ENEMY_ID, COL_WAR_START_DAY, COL_WAR_SELF_POP,
                COL_WAR_ENEMY_POP, COL_WAR_AGGRESSOR, COL_UPDATED_AT
        });
    }
}
