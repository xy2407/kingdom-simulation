package com.xy2407.kingdom.common.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 持久层生命周期接线，按服务端实例管理开库、定时落盘与关服刷盘
 */
public final class KingdomPersistence {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLUSH_INTERVAL_TICKS = 20;

    private static final List<Migration> MIGRATIONS = new ArrayList<>();
    private static final Map<MinecraftServer, KingdomPersistence> INSTANCES = new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> SHUTDOWN = ConcurrentHashMap.newKeySet();
    private static volatile KingdomPersistence current;

    private final MinecraftServer server;
    private Database db;
    private PersistQueue queue;
    private boolean opened;
    private boolean stopping;
    private boolean dropWarned;
    private int tickCounter;

    private KingdomPersistence(MinecraftServer server) {
        this.server = server;
    }

    public static void registerMigration(Migration migration) {
        MIGRATIONS.add(migration);
    }

    public static KingdomPersistence get(MinecraftServer server) {
        if (server == null || SHUTDOWN.contains(server)) {
            return null;
        }
        return INSTANCES.computeIfAbsent(server, KingdomPersistence::new);
    }

    public static KingdomPersistence current() {
        return current;
    }

    public static void clearServerCache(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SHUTDOWN.add(server);
        KingdomPersistence removed = INSTANCES.remove(server);
        if (removed != null) {
            removed.close();
        }
        if (current == removed) {
            current = null;
        }
    }

    public static void forgetServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        INSTANCES.remove(server);
        SHUTDOWN.remove(server);
        current = null;
    }

    public static void submit(String table, String keyColumn, String key, Map<String, Object> values) {
        KingdomPersistence instance = current;
        if (instance != null) {
            instance.doSubmit(table, keyColumn, key, values);
        }
    }

    public static void submitNow(String table, String keyColumn, String key, Map<String, Object> values) {
        KingdomPersistence instance = current;
        if (instance != null) {
            instance.doSubmitNow(table, keyColumn, key, values);
        }
    }

    public static void delete(String table, String keyColumn, String key) {
        KingdomPersistence instance = current;
        if (instance != null) {
            instance.doDelete(table, keyColumn, key);
        }
    }

    public static void flushSync() {
        KingdomPersistence instance = current;
        if (instance != null) {
            instance.doFlushSync();
        }
    }

    public static <T> T querySync(SqlQuery<T> query) throws SQLException {
        KingdomPersistence instance = current;
        if (instance == null) {
            throw new IllegalStateException("数据库尚未打开");
        }
        return instance.doQuerySync(query);
    }

    public static boolean isReady() {
        KingdomPersistence instance = current;
        return instance != null && instance.opened;
    }

    public void onServerStarting() {
        current = this;
        stopping = false;
        ensureOpen();
    }

    public void onServerTick() {
        if (!opened) {
            ensureOpen();
        }
        if (!opened || stopping) {
            return;
        }
        if (++tickCounter % FLUSH_INTERVAL_TICKS != 0) {
            return;
        }
        queue.triggerFlush();
    }

    public void onServerStopping() {
        if (!opened) {
            return;
        }
        stopping = true;
        queue.flushSync();
        queue.shutdown();
        db.closeQuietly();
        opened = false;
    }

    public void close() {
        onServerStopping();
    }

    public boolean isOpened() {
        return opened;
    }

    public boolean isDegraded() {
        return queue != null && queue.isDegraded();
    }

    private void doSubmit(String table, String keyColumn, String key, Map<String, Object> values) {
        if (opened && !stopping) {
            queue.submit(table, keyColumn, key, values);
            return;
        }
        warnDropped("写入", table);
    }

    private void doSubmitNow(String table, String keyColumn, String key, Map<String, Object> values) {
        if (opened && !stopping) {
            queue.submitPriority(table, keyColumn, key, values);
            return;
        }
        warnDropped("写入", table);
    }

    private void doDelete(String table, String keyColumn, String key) {
        if (opened && !stopping) {
            queue.delete(table, keyColumn, key);
            return;
        }
        warnDropped("删除", table);
    }

    private void doFlushSync() {
        if (opened) {
            queue.flushSync();
        }
    }

    private <T> T doQuerySync(SqlQuery<T> query) throws SQLException {
        // 仅启动LOAD/维护用，业务运行期查询走内存
        if (db == null) {
            throw new IllegalStateException("数据库尚未打开");
        }
        return db.querySync(query);
    }

    private void warnDropped(String action, String table) {
        if (!dropWarned) {
            dropWarned = true;
            LOGGER.warn("持久层未就绪，丢弃{}请求: table={} opened={} stopping={}", action, table, opened, stopping);
        }
    }

    private void ensureOpen() {
        if (opened || stopping) {
            return;
        }
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        if (worldPath == null || !Files.isDirectory(worldPath)) {
            return;
        }
        Path dbPath = worldPath.resolve("kingdom").resolve("kingdom.db");
        Database database = new Database();
        try {
            database.open(dbPath, MIGRATIONS);
        } catch (Exception e) {
            LOGGER.error("数据库初始化失败，本次运行跳过持久化", e);
            return;
        }
        this.db = database;
        this.queue = new PersistQueue(database);
        opened = true;
        dropWarned = false;
        LOGGER.info("王国数据库已就绪: {}", dbPath);
    }
}