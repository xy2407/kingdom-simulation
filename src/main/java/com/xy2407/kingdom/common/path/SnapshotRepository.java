package com.xy2407.kingdom.common.path;

import java.util.HashMap;
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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

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

    private static final int MAX_SEARCHES_PER_TICK = 80;
    private static final int MAX_URGENT_SEARCHES_PER_TICK = 24;
    private static final int SEARCH_THREADS = 4;
    private static final int URGENT_THREADS = 2;

    private final AtomicInteger threadSeq = new AtomicInteger();
    private final Map<String, LongOpenHashSet> requestedUnits = new HashMap<>();
    private int searchesThisTick;
    private int urgentSearchesThisTick;
    private int commuteSearchesThisTick;
    private ExecutorService executor;
    private ExecutorService urgentExecutor;

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
        urgentSearchesThisTick = 0;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(SEARCH_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "kingdom-path-" + threadSeq.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }
        if (urgentExecutor == null || urgentExecutor.isShutdown()) {
            urgentExecutor = Executors.newFixedThreadPool(URGENT_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "kingdom-path-urgent-" + threadSeq.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
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
        ExecutorService urgent = urgentExecutor;
        urgentExecutor = null;
        if (urgent != null) {
            urgent.shutdownNow();
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
        urgentSearchesThisTick = 0;
        commuteSearchesThisTick = 0;
        requestedUnits.clear();
    }

    /** 紧急配额：与日常寻路的 40/tick 完全独立，玩家 RTS 指令专用，不会被城市寻路挤掉。 */
    public boolean tryAcquireUrgentSearch() {
        if (urgentSearchesThisTick >= MAX_URGENT_SEARCHES_PER_TICK) {
            return false;
        }
        urgentSearchesThisTick++;
        return true;
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
        LongOpenHashSet seen = requestedUnits.computeIfAbsent(dim, key -> new LongOpenHashSet());
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
        return submitSessionSearch(level, session, start, end, padding, false);
    }

    /**
     * urgent = 玩家 RTS 指令：走独立配额与独立线程池。
     * 以前所有寻路（玩家的 + 全城日常的）共享一个 40/tick 配额和 4 线程池，
     * 城市一忙玩家的指令就在队尾挨饿，表现为"有的 NPC 秒动、有的要等两秒"。
     */
    public Future<NpcPathfinder.PathResult> submitSessionSearch(ServerLevel level, NpcPathfinder.Session session,
                                                                BlockPos start, BlockPos end, int padding,
                                                                boolean urgent) {
        ExecutorService current = urgent ? urgentExecutor : executor;
        if (current == null || current.isShutdown()) {
            return null;
        }
        ResourceKey<Level> dimension = level.dimension();
        boolean acquired = urgent ? tryAcquireUrgentSearch() : tryAcquireSearch();
        if (!acquired) {
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
