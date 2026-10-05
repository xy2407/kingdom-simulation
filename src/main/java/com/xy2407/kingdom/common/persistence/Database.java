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

    /**
     * 核对表结构：<b>每次启动把所有迁移都跑一遍</b>，不再按 {@code user_version} 做闸门。
     *
     * <p><b>为什么不能再用 {@code version <= current → 跳过}。</b>那样会漏表。
     * 数据库是一路升上来的（{@code user_version} 可能已经是 15），而后补进来的 schema
     * 若给了较小的版本号（例如 {@code farm_land=9}、{@code ranch_pen=10}、{@code farm_plot=11}），
     * 它们的 version 永远 ≤ current，于是 {@code apply()} 永远不跑、
     * <b>表永远建不出来</b>——表现就是"代码里定义了 8 张表，库里只有 5 张"。</p>
     *
     * <p><b>为什么全跑是安全的。</b>所有 {@code apply()} 都是幂等的：
     * 只有 {@code CREATE TABLE IF NOT EXISTS} 和"PRAGMA 查过列再去 ALTER"的补列，
     * 不含任何 {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code DROP}/{@code RENAME}。
     * 已有表原样跳过，缺的表补上，反复执行不会重复建也不会动数据。
     * 代价只是启动时多次 PRAGMA 查询，可以忽略。</p>
     *
     * <p>{@code user_version} 现在只当"已见到的最高版本"记录下来供排查用，不再决定执行与否。</p>
     */
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
                // user_version 只是记录用途，写不进去不影响表结构
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

    /**
     * 清掉主键为 {@code NULL} 或空串的废行。
     *
     * <p>废行的来龙去脉见 {@link #executeOp} 里 UPSERT 那段注释：写入端曾经漏写主键，
     * 而 SQLite 的 {@code TEXT PRIMARY KEY} 允许 NULL、唯一约束又不比较 NULL，
     * 于是攒下了一批"主键为空"的行。</p>
     *
     * <p><b>为什么必须清掉而不是只在读取端跳过。</b>读取端跳过只能让表"能读"，
     * 但这些行会永远留在库里，而且每次 {@code INSERT} 都命中不了冲突、继续新增。
     * 修好写入端之后再把存量清掉，才算干净。</p>
     *
     * <p>表还没建出来（首次运行、或迁移顺序在前）时直接忽略——那时本来也没有废行。</p>
     */
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
            // 表不存在 / 没有该列：没东西可清，不影响迁移
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
                /* **兜底补主键**——这是本次修复的核心。
                 *
                 * 列清单原本直接取自 op.getValues()，而 submit() 传进来的 key 参数
                 * 此前只有 DELETE 分支在用、UPSERT 分支完全丢弃。于是 toValues()
                 * 一旦漏掉主键列，写进去的主键就是 **NULL**；而 SQLite 的
                 * `TEXT PRIMARY KEY` **不隐含 NOT NULL**（只有 INTEGER PRIMARY KEY 才隐含），
                 * 唯一约束又**不比较 NULL** —— 两头一凑，ON CONFLICT 永远命不中，
                 * 每写一次就新增一行主键为 NULL 的废行。
                 *
                 * 读取端随即遭殃：UUID.fromString(null) 直接抛 NPE、
                 * 或拿 null 当 ConcurrentHashMap 的 key —— 整张表的加载被打死，
                 * 注册表 isLoaded() 永远是 false。表现就是"国家/领地/玩家档案读不出来"。
                 *
                 * 这里统一把 keyColumn 补进 values，从源头堵死**所有**表，
                 * 不必再依赖每个 toValues() 记得写主键。
                 */
                Map<String, Object> values = new LinkedHashMap<>(op.getValues());
                values.putIfAbsent(op.getKeyColumn(), op.getKey());

                String columns = String.join(", ", values.keySet());
                String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));
                /* 主键列不放进 DO UPDATE：它是冲突判据本身，更新自己没有意义 */
                String assignments = String.join(", ", values.keySet().stream()
                        .filter(column -> !column.equals(op.getKeyColumn()))
                        .map(column -> column + "=excluded." + column)
                        .toList());
                /* 极端情况：values 里只有主键一列 → DO UPDATE SET 为空会语法出错 */
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
