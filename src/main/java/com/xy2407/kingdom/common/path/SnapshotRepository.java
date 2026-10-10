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
 * 寻路快照仓库：分帧构建区块快照并兜底轮转重建，路径搜索交由后台线程池执行。
 */
public final class SnapshotRepository {
    private static final Map<MinecraftServer, SnapshotRepository> INSTANCES = new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> SHUTDOWN = ConcurrentHashMap.newKeySet();

    private static final int BLOCKS_PER_TICK = 4096;
    private static final int ESTIMATED_BLOCKS_PER_CHUNK = 2048;
    /** 单区块最低记账成本，避免空区块把预算扣成 0 导致一 tick 无限建快照。 */
    private static final int MIN_BLOCKS_PER_CHUNK = 256;
    private static final int PENDING_QUEUE_CAP = 4096;
    private static final int MAX_TRACKED_CHUNKS = 4096;
    private static final int MAX_SEARCHES_PER_TICK = 20;
    private static final int MAX_COMMUTE_SEARCHES_PER_TICK = 16;
    private static final int SEARCH_THREADS = 2;
    private static final long DIRTY_COOLDOWN_TICKS = 4L;

    private final Map<ResourceKey<Level>, Store> stores = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private int searchesThisTick;
    private int commuteSearchesThisTick;
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

    public boolean tryAcquireSearch() {
        if (searchesThisTick >= MAX_SEARCHES_PER_TICK) {
            return false;
        }
        searchesThisTick++;
        return true;
    }

    public void resetCommuteSearches() {
        commuteSearchesThisTick = 0;
    }

    private boolean tryAcquireCommuteSearch() {
        if (commuteSearchesThisTick >= MAX_COMMUTE_SEARCHES_PER_TICK) {
            return false;
        }
        commuteSearchesThisTick++;
        return true;
    }

    public void releaseSearch() {
    }

    public boolean hasDemand(ServerLevel level) {
        return searchesThisTick > 0 || !store(level.dimension()).pending.isEmpty();
    }

    public Future<List<BlockPos>> submitSearch(ServerLevel level, BlockPos start, BlockPos end) {
        return submitSearch(level, start, end, NpcPathfinder.DEFAULT_PADDING);
    }

    public Future<List<BlockPos>> submitSearch(ServerLevel level, BlockPos start, BlockPos end, int padding) {
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        if (!NpcPathfinder.isViable(this, dimension, start, end)) {
            return null;
        }
        if (!tryAcquireSearch()) {
            return null;
        }
        NpcPathfinder.requestChunks(this, dimension, start, end, padding);
        try {
            return current.submit(() -> NpcPathfinder.find(this, dimension, start, end, padding));
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    /**
     * 提交一次可续跑的会话搜索：升档重试时 Session 会接着上次的搜索图往下跑，
     * 而不是从起点重算。调用方必须保证同一个 Session 不被并发提交。
     */
    public Future<NpcPathfinder.PathResult> submitSessionSearch(ServerLevel level, NpcPathfinder.Session session,
                                                                BlockPos start, BlockPos end, int padding) {
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        if (!NpcPathfinder.isViable(this, dimension, start, end)) {
            return null;
        }
        if (!tryAcquireSearch()) {
            return null;
        }
        NpcPathfinder.requestChunks(this, dimension, start, end, padding);
        try {
            return current.submit(() -> session.advance(this, dimension, padding));
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    public Future<List<BlockPos>> submitCommuteSearch(ServerLevel level, BlockPos start, BlockPos end) {
        return submitCommuteSearch(level, start, end, NpcPathfinder.DEFAULT_PADDING);
    }

    public Future<List<BlockPos>> submitCommuteSearch(ServerLevel level, BlockPos start, BlockPos end, int padding) {
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        if (!NpcPathfinder.isViable(this, dimension, start, end)) {
            return null;
        }
        if (!tryAcquireCommuteSearch()) {
            return null;
        }
        NpcPathfinder.requestChunks(this, dimension, start, end, padding);
        try {
            return current.submit(() -> NpcPathfinder.find(this, dimension, start, end, padding));
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    public void tick(ServerLevel level) {
        searchesThisTick = 0;
        Store store = store(level.dimension());
        // 只重建被"打脏"的区块（markDirty / LevelChunkMixin）。
        // 原来这里还有一段"无条件轮转重建全部快照"的兜底，它让主线程只要有寻路需求
        // 就恒定以 1 区块/tick 的速度空转；现在方块变化有了全量覆盖，那段轮转纯属浪费。
        drain(level, store, BLOCKS_PER_TICK);
    }

    public ChunkSnapshot getSnapshot(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return store(dimension).snapshots.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public ChunkSnapshot getSnapshot(ServerLevel level, int chunkX, int chunkZ) {
        return getSnapshot(level.dimension(), chunkX, chunkZ);
    }

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

    public void markDirty(ServerLevel level, int chunkX, int chunkZ) {
        Store store = store(level.dimension());
        long id = ChunkPos.asLong(chunkX, chunkZ);
        // 这个入口挂在所有方块变化上（LevelChunkMixin，每次挖掘/放置/爆炸都会进），
        // 必须让绝大多数调用在这里就掉头：没建过快照的区块压根不用记录。
        if (!store.snapshots.containsKey(id)) {
            return;
        }
        long now = level.getGameTime();
        Long last = store.dirtyAt.get(id);
        if (last != null && now - last < DIRTY_COOLDOWN_TICKS) {
            return;
        }
        store.dirtyAt.put(id, now);
        offer(store, id);
    }

    public void refreshNow(ServerLevel level, BlockPos pos) {
        offer(store(level.dimension()), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
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
            ChunkSnapshot snapshot = ChunkSnapshot.build(chunk);
            store.snapshots.put(id, snapshot);
            // 按实际扫描量扣预算：原来固定扣 2048，可高楼区块真实要扫到 8192，
            // 分帧就失真了 —— 一 tick 内可能吃掉 4 倍的预算。
            budget -= Math.max(snapshot.scanCost(), MIN_BLOCKS_PER_CHUNK);
        }
        trim(store);
        return budget;
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
        /** 上次打脏的时间戳，用于 DIRTY_COOLDOWN_TICKS 防抖（同一区块 4 tick 内只重建一次）。 */
        private final Map<Long, Long> dirtyAt = new ConcurrentHashMap<>();
    }
}
