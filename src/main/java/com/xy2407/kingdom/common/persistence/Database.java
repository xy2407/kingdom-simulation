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
        int current = readUserVersion();
        for (Migration migration : sorted) {
            if (migration.version() <= current) {
                continue;
            }
            conn.setAutoCommit(false);
            try {
                migration.apply(conn);
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("PRAGMA user_version=" + migration.version());
                }
                conn.commit();
                LOGGER.info("数据库迁移已应用到 v{}", migration.version());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private int readUserVersion() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
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
                String columns = String.join(", ", op.getValues().keySet());
                String placeholders = String.join(", ", Collections.nCopies(op.getValues().size(), "?"));
                String assignments = String.join(", ", op.getValues().keySet().stream().map(c -> c + "=excluded." + c).toList());
                String sql = "INSERT INTO " + op.getTable() + " (" + columns + ") VALUES (" + placeholders + ")" +
                        " ON CONFLICT (" + op.getKeyColumn() + ") DO UPDATE SET " + assignments;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int index = 1;
                    for (Object value : op.getValues().values()) {
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
