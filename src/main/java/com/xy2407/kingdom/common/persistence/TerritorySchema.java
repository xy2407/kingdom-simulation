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
 * 领地表迁移：建 territory 表、幂等补列。
 *
 * <p><b>领地是国家的下属</b>：{@code nation_id} 指向玩家首登时选定的国家；
 * 玩家自己建国的话，指向的就是自己建的那个。</p>
 *
 * <p><b>范围记的是区块，不是方块</b>：以市政厅所在区块为中心、向四周各扩 2 格，
 * 即 5×5 共 25 个水平区块。四个 {@code *_chunk_*} 列存这个矩形的闭区间角点，
 * 判定"某区块属不属于这块领地"就是一次整数比较，不需要扫方块。</p>
 *
 * <p>当前规则是<b>一个玩家一块领地</b>，所以 {@code owner_uuid} 上加唯一约束不现实
 * （玩家可以有自己的第二块，将来放开时不必改表），改由 {@code TerritoryRegistry} 在
 * 创建时做"已有则拒绝"的校验。</p>
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
    public static final String COL_CENTER_Z = "center_z";
    public static final String COL_MIN_CHUNK_X = "min_chunk_x";
    public static final String COL_MIN_CHUNK_Z = "min_chunk_z";
    public static final String COL_MAX_CHUNK_X = "max_chunk_x";
    public static final String COL_MAX_CHUNK_Z = "max_chunk_z";
    public static final String COL_CREATED_DAY = "created_day";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        return 13;
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
        defs.put(COL_CENTER_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MIN_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_X, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_MAX_CHUNK_Z, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_CREATED_DAY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_ID, COL_NAME, COL_NATION_ID, COL_OWNER_UUID, COL_DIMENSION,
                COL_CENTER_X, COL_CENTER_Z, COL_MIN_CHUNK_X, COL_MIN_CHUNK_Z,
                COL_MAX_CHUNK_X, COL_MAX_CHUNK_Z, COL_CREATED_DAY, COL_UPDATED_AT
        });
    }
}
