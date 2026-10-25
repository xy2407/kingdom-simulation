package com.xy2407.kingdom.common.path;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.xy2407.kingdom.server.path.UnitSnapshotStore;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 寻路搜索调度壳：持有后台线程池与每 tick 搜索配额，快照数据统一由 UnitSnapshotStore 供给。
 */
public final class SnapshotRepository {
    private static final Map<MinecraftServer, SnapshotRepository> INSTANCES = new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> SHUTDOWN = ConcurrentHashMap.newKeySet();

    private static final int MAX_SEARCHES_PER_TICK = 40;
    private static final int SEARCH_THREADS = 4;

    private final AtomicInteger threadSeq = new AtomicInteger();
    private final Map<String, Set<Long>> requestedUnits = new HashMap<>();
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

    public void resetTickSearches() {
        searchesThisTick = 0;
        commuteSearchesThisTick = 0;
        requestedUnits.clear();
    }

    public void resetCommuteSearches() {
        commuteSearchesThisTick = 0;
    }

    public void releaseSearch() {
    }

    public <T> Future<T> submit(Supplier<T> task) {
        ExecutorService current = executor;
        if (task == null || current == null || current.isShutdown() || !tryAcquireSearch()) {
            return null;
        }
        return current.submit(task::get);
    }

    public void requestUnits(ResourceKey<Level> dimension, BlockPos start, BlockPos end, int padding) {
        String dim = dimension.location().toString();
        Set<Long> seen = requestedUnits.computeIfAbsent(dim, key -> new HashSet<>());
        int minX = Math.min(start.getX(), end.getX()) - padding;
        int maxX = Math.max(start.getX(), end.getX()) + padding;
        int minZ = Math.min(start.getZ(), end.getZ()) - padding;
        int maxZ = Math.max(start.getZ(), end.getZ()) + padding;
        int minY = Math.min(start.getY(), end.getY()) - padding;
        int maxY = Math.max(start.getY(), end.getY()) + padding;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                for (int layer = minY >> 4; layer <= maxY >> 4; layer++) {
                    long packed = (((long) cx) << 42) ^ (((long) layer) << 21) ^ (cz & 0x1FFFFFL);
                    if (!seen.add(packed)) {
                        continue;
                    }
                    UnitSnapshotStore.INSTANCE.request(dim, cx, layer, cz);
                }
            }
        }
    }

    public Future<NpcPathfinder.PathResult> submitSessionSearch(ServerLevel level, NpcPathfinder.Session session,
                                                                BlockPos start, BlockPos end, int padding) {
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        if (!tryAcquireSearch()) {
            return null;
        }
        requestUnits(dimension, start, end, padding);
        try {
            return current.submit(() -> session.advance(this, dimension, padding));
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }
}
