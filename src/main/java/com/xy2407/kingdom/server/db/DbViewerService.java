package com.xy2407.kingdom.server.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.db.DbViewerDataPacket;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

/**
 * 数据库表快照服务，按白名单表名整表读取并分块下发
 */
public final class DbViewerService {
    public static final String TABLE_NPC = NpcSchema.TABLE_NPC;
    public static final String TABLE_BUILDING = BuildingSchema.TABLE_BUILDING;
    public static final List<String> TABLES = List.of(TABLE_NPC, TABLE_BUILDING);

    private static final Set<String> ALLOWED = Set.of(TABLE_NPC, TABLE_BUILDING);
    private static final int MAX_ROWS = 500;
    private static final int ROWS_PER_CHUNK = 100;
    private static final int CELL_LIMIT = 96;

    private DbViewerService() {
    }

    public static boolean isAllowed(String table) {
        return table != null && ALLOWED.contains(table);
    }

    public static List<DbViewerDataPacket> snapshot(String table) {
        if (!isAllowed(table)) {
            return List.of();
        }
        try {
            return KingdomPersistence.querySync(connection -> read(connection, table));
        } catch (SQLException | IllegalStateException exception) {
            ModMain.LOGGER.error("数据库表快照失败: {}", table, exception);
            return List.of();
        }
    }

    private static List<DbViewerDataPacket> read(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        List<List<String>> rows = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
            int count = rs.getMetaData().getColumnCount();
            while (rs.next() && rows.size() < MAX_ROWS) {
                List<String> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    row.add(cell(rs.getObject(i)));
                }
                rows.add(row);
            }
        }
        int chunkTotal = Math.max(1, (int) Math.ceil(rows.size() / (double) ROWS_PER_CHUNK));
        List<DbViewerDataPacket> packets = new ArrayList<>(chunkTotal);
        for (int index = 0; index < chunkTotal; index++) {
            int from = index * ROWS_PER_CHUNK;
            int to = Math.min(rows.size(), from + ROWS_PER_CHUNK);
            packets.add(new DbViewerDataPacket(table, index, chunkTotal, columns,
                    rows.subList(from, to)));
        }
        return packets;
    }

    private static String cell(Object value) {
        if (value == null) {
            return "NULL";
        }
        String text = String.valueOf(value);
        return text.length() <= CELL_LIMIT ? text : text.substring(0, CELL_LIMIT) + "…";
    }
}
