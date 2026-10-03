package com.xy2407.kingdom.common.path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 寻路快照仓库，分帧构建区块快照并做兜底轮转重建；路径搜索交由后台线程池执行。
 *
 * <p>搜索提交按「每 tick 放行数」约束：{@value #MAX_SEARCHES_PER_TICK} 个名额每 tick 由
 * {@link #tick} 重置，只用于给主线程的提交动作限流。名额不跨 tick 累积，所以没有回收动作，
 * 也就不存在任务被取消后名额泄漏的问题。</p>
 *
 * <p>快照构建由 {@link #hasDemand} 门控：没有自定义 A* 需求时调用方跳过 {@link #tick}，
 * 常驻开销归零。</p>
 */
public final class SnapshotRepository {
    private static final Map<MinecraftServer, SnapshotRepository> INSTANCES = new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> SHUTDOWN = ConcurrentHashMap.newKeySet();

    private static final int BLOCKS_PER_TICK = 4096;
    private static final int REBUILD_BLOCKS_PER_TICK = 2048;
    private static final int ESTIMATED_BLOCKS_PER_CHUNK = 2048;
    private static final int PENDING_QUEUE_CAP = 4096;
    private static final int MAX_TRACKED_CHUNKS = 4096;
    private static final int MAX_SEARCHES_PER_TICK = 20;
    private static final int SEARCH_THREADS = 2;
    /** 同一区块两次快照重建之间的最短间隔（约 4 tick）。 */
    private static final long DIRTY_COOLDOWN_NANOS = 200_000_000L;

    private final Map<ResourceKey<Level>, Store> stores = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    /** 本 tick 已放行的搜索数，由 {@link #tick} 每 tick 清零，只在主线程读写。 */
    private int searchesThisTick;
    private ExecutorService executor;

    private SnapshotRepository() {
    }

    public static SnapshotRepository get(MinecraftServer server) {
        if (server == null || SHUTDOWN.contains(server)) {
            return null;
        }
        return INSTANCES.computeIfAbsent(server, s -> new SnapshotRepository());
    }

    public static void clearServerCache(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SHUTDOWN.add(server);
        SnapshotRepository removed = INSTANCES.remove(server);
        if (removed != null) {
            removed.onServerStopping();
        }
    }

    public static void forgetServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        INSTANCES.remove(server);
        SHUTDOWN.remove(server);
    }

    public void onServerStarting() {
        stores.clear();
        searchesThisTick = 0;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(SEARCH_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "kingdom-path-" + threadSeq.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }
    }

    public void onServerStopping() {
        stores.clear();
        searchesThisTick = 0;
        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 尝试占本 tick 的一个搜索名额。成功返回 true；名额每 tick 自动重置。 */
    public boolean tryAcquireSearch() {
        if (searchesThisTick >= MAX_SEARCHES_PER_TICK) {
            return false;
        }
        searchesThisTick++;
        return true;
    }

    /** 与 {@link #tryAcquireSearch()} 配对；名额按 tick 重置，这里不再回收。 */
    public void releaseSearch() {
    }

    /** 还有没有人在等自定义 A*：本 tick 有放行，或待建队列非空。 */
    public boolean hasDemand(ServerLevel level) {
        return searchesThisTick > 0 || !store(level.dimension()).pending.isEmpty();
    }

    /**
     * 提交后台 A* 搜索，并预先把搜索区域所需的区块加入快照队列；仅在主线程调用
     *
     * @return 搜索任务；没有名额、起终点不可站或线程池不可用时返回 null
     */
    public Future<List<BlockPos>> submitSearch(ServerLevel level, BlockPos start, BlockPos end) {
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        // 廉价预检：起终点若在已有快照里根本落不到可站立层，这次搜索必然空手而归，
        // 直接否决，省掉一整轮最多 MAX_NODES 次扩张。
        if (!NpcPathfinder.isViable(this, dimension, start, end)) {
            return null;
        }
        if (!tryAcquireSearch()) {
            return null;
        }
        NpcPathfinder.requestChunks(this, dimension, start, end);
        try {
            return current.submit(() -> NpcPathfinder.find(this, dimension, start, end));
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    /**
     * 分帧建快照。
     *
     * <p>本方法由 {@link #hasDemand} 门控：没有自定义 A* 需求时调用方会跳过它，轮转重建
     * 也一并停下。代价是静默期间发生、又没触发 {@code SnapshotEvents} 的地形变化（活塞、
     * 流体、爆炸、{@code /setblock}）不再有「最多一个轮转周期」的自愈兜底，要等下一次
     * 真正有人寻路、轮转重新转起来才会被吸收。想恢复无条件轮转，把调用方的 hasDemand
     * 判断去掉即可。</p>
     */
    public void tick(ServerLevel level) {
        searchesThisTick = 0;
        Store store = store(level.dimension());
        int budget = drain(level, store, BLOCKS_PER_TICK);
        if (budget > 0) {
            enqueueRotation(store);
            drain(level, store, budget);
        }
    }

    /** 无副作用读取，可在后台线程调用 */
    public ChunkSnapshot getSnapshot(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return store(dimension).snapshots.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public ChunkSnapshot getSnapshot(ServerLevel level, int chunkX, int chunkZ) {
        return getSnapshot(level.dimension(), chunkX, chunkZ);
    }

    /** 仅在主线程调用；已有快照的区块跳过，避免寻路批量请求把待建队列冲爆 */
    public void enqueue(ResourceKey<Level> dimension, ChunkPos pos) {
        Store store = store(dimension);
        long id = pos.toLong();
        if (store.snapshots.containsKey(id)) {
            return;
        }
        offer(store, id);
    }

    public void enqueue(ServerLevel level, ChunkPos pos) {
        enqueue(level.dimension(), pos);
    }

    public void markDirty(ServerLevel level, BlockPos pos) {
        markDirty(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * 标记区块快照需要重建（带冷却）。
     *
     * <p>施工期会连续放置成百上千个方块，若逐块把同一区块重新入队，就等于每 tick 重建一次整块
     * 快照（{@code ChunkSnapshot.build} 要扫 256 列全高）。冷却期内只记时间不重复入队，
     * 漏掉的那一次由轮转重建 {@link #enqueueRotation} 兜底吸收，不会丢更新。</p>
     */
    public void markDirty(ServerLevel level, int chunkX, int chunkZ) {
        Store store = store(level.dimension());
        long id = ChunkPos.asLong(chunkX, chunkZ);
        long now = System.nanoTime();
        Long last = store.dirtyAt.get(id);
        if (last != null && now - last < DIRTY_COOLDOWN_NANOS) {
            return;
        }
        store.dirtyAt.put(id, now);
        offer(store, id);
    }

    public void drop(ServerLevel level, ChunkPos pos) {
        Store store = store(level.dimension());
        long id = pos.toLong();
        store.snapshots.remove(id);
        store.pendingIds.remove(id);
        store.pending.remove(id);
    }

    private Store store(ResourceKey<Level> dimension) {
        return stores.computeIfAbsent(dimension, key -> new Store());
    }

    private void offer(Store store, long id) {
        if (!store.pendingIds.add(id)) {
            return;
        }
        if (store.pending.size() >= PENDING_QUEUE_CAP) {
            store.pendingIds.remove(store.pending.poll());
        }
        store.pending.add(id);
    }

    private int drain(ServerLevel level, Store store, int budget) {
        int scan = store.pending.size();
        while (budget > 0 && scan-- > 0) {
            long id = store.pending.poll();
            store.pendingIds.remove(id);
            ChunkPos pos = new ChunkPos(id);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                continue;
            }
            store.snapshots.put(id, ChunkSnapshot.build(chunk));
            budget -= ESTIMATED_BLOCKS_PER_CHUNK;
        }
        trim(store);
        return budget;
    }

    private void enqueueRotation(Store store) {
        if (store.snapshots.isEmpty()) {
            return;
        }
        if (store.cycleCursor >= store.cycle.size()) {
            store.cycle.clear();
            store.cycle.addAll(store.snapshots.keySet());
            store.cycleCursor = 0;
            if (store.cycle.isEmpty()) {
                return;
            }
        }
        int quota = Math.min(REBUILD_BLOCKS_PER_TICK / ESTIMATED_BLOCKS_PER_CHUNK, store.cycle.size() - store.cycleCursor);
        for (int i = 0; i < quota; i++) {
            offer(store, store.cycle.get(store.cycleCursor++));
        }
    }

    private void trim(Store store) {
        while (store.snapshots.size() > MAX_TRACKED_CHUNKS) {
            Long eldest = null;
            for (Long key : store.snapshots.keySet()) {
                eldest = key;
                break;
            }
            if (eldest == null) {
                return;
            }
            store.snapshots.remove(eldest);
            store.pendingIds.remove(eldest);
            store.dirtyAt.remove(eldest);
        }
    }

    private static final class Store {
        private final Map<Long, ChunkSnapshot> snapshots = new ConcurrentHashMap<>();
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        private final Set<Long> pendingIds = new HashSet<>();
        private final List<Long> cycle = new ArrayList<>();
        /** 各区块上次因标脏而重建的纳秒时刻，用于 {@link #markDirty} 的冷却。 */
        private final Map<Long, Long> dirtyAt = new ConcurrentHashMap<>();
        private int cycleCursor;
    }
}
