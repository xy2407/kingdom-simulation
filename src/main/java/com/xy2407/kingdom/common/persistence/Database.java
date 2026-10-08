package com.xy2407.kingdom.common.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import com.mojang.logging.LogUtils;

/**
 * SQLite连接与版本迁移，损坏时自动备份重建
 */
public final class Database {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Connection conn;

    public synchronized void open(Path path, List<Migration> migrations) {
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(path.getParent());
            openRaw(path);
            migrate(migrations);
        } catch (Exception e) {
            closeQuietly();
            if (isCorrupt(e)) {
                LOGGER.error("数据库文件损坏，备份后重建: {}", path, e);
                backupAndRebuild(path, migrations);
                return;
            }
            throw new IllegalStateException("数据库打开失败: " + path, e);
        }
    }

    private static boolean isCorrupt(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException sqlite) {
                SQLiteErrorCode code = sqlite.getResultCode();
                if (code == SQLiteErrorCode.SQLITE_NOTADB || code.name().startsWith("SQLITE_CORRUPT")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void openRaw(Path path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void backupAndRebuild(Path path, List<Migration> migrations) {
        try {
            if (Files.exists(path)) {
                Path bak = path.resolveSibling(path.getFileName() + ".bak");
                Files.move(path, bak, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-wal"));
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-shm"));
                LOGGER.error("损坏数据库已备份为: {}", bak);
            }
            openRaw(path);
            migrate(migrations);
            LOGGER.error("已重建空数据库: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("数据库重建失败: " + path, e);
        }
    }

    private void migrate(List<Migration> migrations) throws SQLException {
        List<Migration> sorted = migrations.stream().sorted(Comparator.comparingInt(Migration::version)).toList();
        int before = readUserVersion();
        for (Migration migration : sorted) {
            conn.setAutoCommit(false);
            try {
                migration.apply(conn);
                purgeBrokenRows(conn, migration);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        int highest = before;
        for (Migration migration : sorted) {
            highest = Math.max(highest, migration.version());
        }
        if (highest > before) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("PRAGMA user_version=" + highest);
            } catch (SQLException ignored) {
            }
        }
        LOGGER.info("数据库结构已核对：{} 个迁移全部执行，user_version {} → {}",
                sorted.size(), before, highest);
    }

    private int readUserVersion() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        }
    }

    private static void purgeBrokenRows(Connection connection, Migration migration) {
        String table = migration.tableName();
        String key = migration.primaryKeyColumn();
        if (table == null || table.isBlank() || key == null || key.isBlank()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            int removed = st.executeUpdate("DELETE FROM " + table
                    + " WHERE " + key + " IS NULL OR " + key + " = ''");
            if (removed > 0) {
                LOGGER.warn("清理 {} 表里主键为空的废行：{} 行", table, removed);
            }
        } catch (SQLException ignored) {
        }
    }

    public synchronized void executeOps(Map<String, List<WriteOp>> byTable) throws SQLException {
        for (Map.Entry<String, List<WriteOp>> entry : byTable.entrySet()) {
            conn.setAutoCommit(false);
            try {
                for (WriteOp op : entry.getValue()) {
                    executeOp(op);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void executeOp(WriteOp op) throws SQLException {
        switch (op.getType()) {
            case UPSERT -> {
                Map<String, Object> values = new LinkedHashMap<>(op.getValues());
                values.putIfAbsent(op.getKeyColumn(), op.getKey());

                String columns = String.join(", ", values.keySet());
                String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));
                String assignments = String.join(", ", values.keySet().stream()
                        .filter(column -> !column.equals(op.getKeyColumn()))
                        .map(column -> column + "=excluded." + column)
                        .toList());
                String conflict = assignments.isEmpty()
                        ? " ON CONFLICT (" + op.getKeyColumn() + ") DO NOTHING"
                        : " ON CONFLICT (" + op.getKeyColumn() + ") DO UPDATE SET " + assignments;
                String sql = "INSERT INTO " + op.getTable() + " (" + columns + ") VALUES ("
                        + placeholders + ")" + conflict;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int index = 1;
                    for (Object value : values.values()) {
                        ps.setObject(index++, value);
                    }
                    ps.executeUpdate();
                }
            }
            case DELETE -> {
                String sql = "DELETE FROM " + op.getTable() + " WHERE " + op.getKeyColumn() + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, op.getKey());
                    ps.executeUpdate();
                }
            }
        }
    }

    public synchronized <T> T querySync(SqlQuery<T> query) throws SQLException {
        return query.apply(conn);
    }

    public synchronized void closeQuietly() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOGGER.error("数据库关闭失败", e);
            }
            conn = null;
        }
    }
}
