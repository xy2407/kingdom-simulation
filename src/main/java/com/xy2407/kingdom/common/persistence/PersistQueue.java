package com.xy2407.kingdom.common.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 单写线程落盘队列，双缓冲去重、按表事务与降级保护
 */
public final class PersistQueue {
    private static final int MAX_BUFFER_SIZE = 500;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long DEGRADED_PROBE_INTERVAL_MILLIS = 30_000L;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Database db;
    private final AtomicInteger bufferSize = new AtomicInteger();
    private final AtomicBoolean flushingNow = new AtomicBoolean(false);
    private final BlockingQueue<Batch> batches = new LinkedBlockingQueue<>();
    private final Map<String, Map<String, WriteOp>> pending = new LinkedHashMap<>();
    private final Thread writer;
    private volatile Map<String, Map<String, WriteOp>> active = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile boolean degraded;
    private volatile boolean closed;
    private int consecutiveFailures;
    private long lastReplayAttempt;

    public PersistQueue(Database db) {
        this.db = db;
        this.writer = new Thread(this::run, "Kingdom-DB-Writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public void submit(String table, String keyColumn, String key, Map<String, Object> values) {
        if (closed) {
            return;
        }
        Map<String, WriteOp> bucket = active.computeIfAbsent(table, t -> new ConcurrentHashMap<>());
        if (bucket.put(key, WriteOp.upsert(table, keyColumn, key, values)) == null
                && bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
            triggerFlush();
        }
    }

    public void delete(String table, String keyColumn, String key) {
        if (closed) {
            return;
        }
        Map<String, WriteOp> bucket = active.computeIfAbsent(table, t -> new ConcurrentHashMap<>());
        if (bucket.put(key, WriteOp.delete(table, keyColumn, key)) == null
                && bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
            triggerFlush();
        }
    }

    public void submitPriority(String table, String keyColumn, String key, Map<String, Object> values) {
        if (closed) {
            return;
        }
        removeActive(table, key);
        enqueue(single(table, WriteOp.upsert(table, keyColumn, key, values)), null, false);
    }

    public void submitPrioritySync(String table, String keyColumn, String key, Map<String, Object> values) {
        if (closed) {
            return;
        }
        removeActive(table, key);
        CountDownLatch latch = new CountDownLatch(1);
        enqueue(single(table, WriteOp.upsert(table, keyColumn, key, values)), latch, false);
        await(latch);
    }

    public void triggerFlush() {
        if (!flushingNow.compareAndSet(false, true)) {
            return;
        }
        Map<String, Map<String, WriteOp>> snapshot = active;
        if (snapshot.isEmpty()) {
            flushingNow.set(false);
            return;
        }
        active = new ConcurrentHashMap<>();
        bufferSize.set(0);
        enqueue(snapshot, null, true);
    }

    public void flushSync() {
        if (closed) {
            return;
        }
        Map<String, Map<String, WriteOp>> snapshot = active;
        active = new ConcurrentHashMap<>();
        bufferSize.set(0);
        CountDownLatch latch = new CountDownLatch(1);
        enqueue(snapshot, latch, false);
        await(latch);
    }

    public void shutdown() {
        closed = true;
        running = false;
        writer.interrupt();
        try {
            writer.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isDegraded() {
        return degraded;
    }

    private void enqueue(Map<String, Map<String, WriteOp>> data, CountDownLatch latch, boolean releaseFlushGuard) {
        batches.add(new Batch(data, latch, releaseFlushGuard));
    }

    private void removeActive(String table, String key) {
        Map<String, WriteOp> bucket = active.get(table);
        if (bucket != null && bucket.remove(key) != null) {
            bufferSize.decrementAndGet();
        }
    }

    private static Map<String, Map<String, WriteOp>> single(String table, WriteOp op) {
        Map<String, WriteOp> bucket = new LinkedHashMap<>();
        bucket.put(String.valueOf(op.getKey()), op);
        Map<String, Map<String, WriteOp>> data = new LinkedHashMap<>();
        data.put(table, bucket);
        return data;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (running) {
            Batch batch;
            try {
                batch = batches.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                continue;
            }
            if (batch == null) {
                if (degraded) {
                    replayIfDue();
                }
                continue;
            }
            process(batch);
        }
        List<Batch> rest = new ArrayList<>();
        batches.drainTo(rest);
        for (Batch batch : rest) {
            process(batch);
        }
        replayRetry(3);
        if (!pending.isEmpty()) {
            LOGGER.error("关服重试3次后仍有{}条数据未落盘，滞留内存已随进程释放", pending.size());
        }
    }

    private void process(Batch batch) {
        try {
            if (!batch.data.isEmpty()) {
                mergeIntoPending(batch.data);
                if (!degraded) {
                    writePending();
                }
            }
            if (batch.latch != null) {
                if (!pending.isEmpty()) {
                    replayOnce();
                }
                batch.latch.countDown();
            }
        } finally {
            if (batch.releaseFlushGuard) {
                flushingNow.set(false);
            }
        }
    }

    private void writePending() {
        try {
            db.executeOps(byTable(pending));
            pending.clear();
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            LOGGER.error("写库失败(连续第{}次)，数据留存内存待重放", consecutiveFailures, e);
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                degraded = true;
                lastReplayAttempt = System.currentTimeMillis();
                LOGGER.error("连续{}次写库失败，进入降级保护：暂停落盘，数据留存内存", consecutiveFailures);
            }
        }
    }

    private void replayIfDue() {
        if (System.currentTimeMillis() - lastReplayAttempt < DEGRADED_PROBE_INTERVAL_MILLIS) {
            return;
        }
        lastReplayAttempt = System.currentTimeMillis();
        replayOnce();
    }

    private void replayOnce() {
        if (pending.isEmpty()) {
            return;
        }
        try {
            db.executeOps(byTable(pending));
            pending.clear();
            consecutiveFailures = 0;
            degraded = false;
            LOGGER.info("写库恢复，滞留数据已全部重放");
        } catch (Exception e) {
            LOGGER.error("重放失败，保持降级保护", e);
        }
    }

    private void replayRetry(int attempts) {
        for (int i = 0; i < attempts && !pending.isEmpty(); i++) {
            replayOnce();
            if (!pending.isEmpty()) {
                sleep(500L);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, List<WriteOp>> byTable(Map<String, Map<String, WriteOp>> merged) {
        Map<String, List<WriteOp>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, WriteOp>> entry : merged.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        return result;
    }

    private void mergeIntoPending(Map<String, Map<String, WriteOp>> data) {
        for (Map.Entry<String, Map<String, WriteOp>> entry : data.entrySet()) {
            Map<String, WriteOp> target = pending.computeIfAbsent(entry.getKey(), t -> new LinkedHashMap<>());
            target.putAll(entry.getValue());
        }
    }

    private static final class Batch {
        private final Map<String, Map<String, WriteOp>> data;
        private final CountDownLatch latch;
        private final boolean releaseFlushGuard;

        private Batch(Map<String, Map<String, WriteOp>> data, CountDownLatch latch, boolean releaseFlushGuard) {
            this.data = data;
            this.latch = latch;
            this.releaseFlushGuard = releaseFlushGuard;
        }
    }
}