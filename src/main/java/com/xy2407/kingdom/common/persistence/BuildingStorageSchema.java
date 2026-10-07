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
 * 建筑仓库表迁移：建 {@code building_storage} 表并幂等补齐后续新增的列。
 *
 * <p><b>一座建筑一行</b>，物品整包存在一个 TEXT 列里（{@code ItemStackHandler} 的 SNBT），
 * 而不是一槽一行。</p>
 *
 * <p>为什么整包存：仓库的槽位是"固定容量的一排格子"，读写永远是整排一起动
 * （打开界面时整体灌入、关掉时整体写回），拆成一槽一行只会让每次同步变成
 * 几十条 UPSERT，而读写模式并不会因此变细。整包 SNBT 也天然保留了物品的
 * 全部 NBT（附魔、自定义名等），不必自己设计列来装。</p>
 *
 * <p>容量另存一列而不是从 SNBT 里数：蓝图元数据的 {@code storage_slots} 可能被改，
 * 用哪一档容量是"上次开界面时定下的"，存下来才能发现不一致并重建。</p>
 */
public final class BuildingStorageSchema implements Migration {
    private static final Map<String, String> COLUMN_DEFS = buildColumnDefs();

    public static final String TABLE_BUILDING_STORAGE = "building_storage";
    /** 主键：建筑 UUID 的字符串形式。 */
    public static final String COL_BUILDING_ID = "building_id";
    /** 物品整包：{@code ItemStackHandler#serializeNBT} 产出的 SNBT 文本。 */
    public static final String COL_INVENTORY = "inventory";
    /** 这份库存上次是按多少格建的。 */
    public static final String COL_CAPACITY = "capacity";
    public static final String COL_UPDATED_AT = "updated_at";

    @Override
    public int version() {
        /* 版本号只用于排序与写进 PRAGMA user_version 供排查；
           Database.migrate 每次启动都会把所有迁移跑一遍（apply 幂等），
           所以给多少都不会导致"表建不出来"。
           当前占用：Npc 5 / Building 7 / FarmLand 9 / Ranch 10 / FarmPlot 11 / Nation 12
           / Territory 13 / PlayerProfile 15 / TerritoryVitals 16。 */
        return 17;
    }

    @Override
    public String tableName() {
        return TABLE_BUILDING_STORAGE;
    }

    @Override
    public String primaryKeyColumn() {
        return COL_BUILDING_ID;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(createTableSql());
        }
        addMissingColumns(connection);
    }

    private static String createTableSql() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + TABLE_BUILDING_STORAGE + " (");
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
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + TABLE_BUILDING_STORAGE + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        if (existing.isEmpty()) {
            throw new SQLException(TABLE_BUILDING_STORAGE + " 表不存在，无法补列");
        }
        if (!existing.contains(COL_BUILDING_ID)) {
            throw new SQLException(TABLE_BUILDING_STORAGE + " 表缺少 " + COL_BUILDING_ID + " 主键列，无法自动补列");
        }
        for (Map.Entry<String, String> column : COLUMN_DEFS.entrySet()) {
            if (existing.contains(column.getKey())) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TABLE_BUILDING_STORAGE + " ADD COLUMN "
                        + column.getKey() + " " + column.getValue());
            }
        }
    }

    private static Map<String, String> buildColumnDefs() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(COL_BUILDING_ID, "TEXT PRIMARY KEY");
        defs.put(COL_INVENTORY, "TEXT NOT NULL DEFAULT ''");
        defs.put(COL_CAPACITY, "INTEGER NOT NULL DEFAULT 0");
        defs.put(COL_UPDATED_AT, "INTEGER NOT NULL DEFAULT 0");
        return defs;
    }

    public static String columns() {
        return String.join(", ", new String[] {
                COL_BUILDING_ID, COL_INVENTORY, COL_CAPACITY, COL_UPDATED_AT
        });
    }
}
