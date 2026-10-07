package com.xy2407.kingdom.server.map;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapDataPacket;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端的地图分发：收到请求就采样，再按片发给提出请求的那名玩家。
 *
 * <p><b>采样在后台线程跑</b>。一张 385×385 的图要过 14.8 万列，每列还得查高度图、
 * 往下扫实体方块——全放主线程就是一次明显卡顿。这里丢到固定线程池，采完再回主线程发包
 * （{@code PacketDistributor} 不是线程安全的）。</p>
 *
 * <p><b>按区域缓存</b>。键是 {@code 维度|原点X|原点Z|边长}，LRU 上限 64 条
 * （每条约 590 KB 的 int 数组，共约 38 MB）。同一块区域反复看——开界面、拖回来、缩放——
 * 都命中缓存。</p>
 *
 * <p><b>异步读世界的取舍</b>：{@code getHeight} / {@code getBlockState} 严格说不是线程安全的，
 * 但它们只读、且采样前会先过 {@code hasChunk}，碰的都是已加载区块。最坏情况是读到
 * 某一 tick 中间状态的方块——对一张地图快照来说可以接受，换来的是主线程不卡。</p>
 */
public final class WorldMapServer {

    /** 采样线程数。主要吃高度图查找，2 条够用也不会把 IO 打满。 */
    private static final int WORKERS = 2;

    /** 缓存条目上限。 */
    private static final int CACHE_LIMIT = 64;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "kingdom-map-sampler");
        thread.setDaemon(true);
        return thread;
    });

    /** 访问序 LinkedHashMap = LRU。所有访问都过 {@code CACHE} 同步块。 */
    private static final Map<String, WorldSurfaceSampler.Snapshot> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WorldSurfaceSampler.Snapshot> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private WorldMapServer() {
    }

    /** 换存档时清缓存——维度名相同但地形不同，留着会读到上一个世界的图。 */
    public static void reset() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * 采样以 (centerX, centerZ) 为中心的区域并按 {@link WorldMapDataPacket#CHUNK_INTS}
     * 切片下发。命中缓存就直接发，不重复采样。
     */
    public static void fulfill(ServerPlayer player, int centerX, int centerZ, int radius,
                               int knownRevision) {
        ServerLevel level = player.serverLevel();
        /* 请求里带的中心就是这名玩家正在看的地方——顺手记进他的档案，每人一份。
           客户端只在拖动松手后发请求，所以存下来的正是"他上次看到哪"，
           下次打开界面就从那儿接着看，而不是每次跳回领地中心。 */
        PlayerProfileStore.INSTANCE.setMapCenter(player.getUUID(), centerX, centerZ);

        String dimension = level.dimension().location().toString();
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        String key = dimension + "|" + originX + "|" + originZ + "|" + size;

        /* 先比一句"你手上那份是不是这一版"。一致就回个空片：这一片自上次下发之后没动过，
           重发整块只是把同样几百 KB 的内容再走一遍网。
           客户端换了看的地方时会把版本号清成负值，所以"区域变了、内容没变"不会被误判。 */
        /* 版本按玩家分：每个人的地图不同，比的自然是<b>他自己那份</b>的版本。 */
        ExploredMapStore.Data data = ExploredMapStore.data(level);
        int serverRevision = data.revision(player.getUUID());
        /* 诊断用，默认不输出——客户端改为"只补缺口"后会请求得比较勤，INFO 会刷屏。
           排查时把日志级别开到 DEBUG 即可：已记录区块数为 0 说明服务端的扫描还没把
           玩家周围的地面记进来。 */
        ModMain.LOGGER.debug("[地图] 请求 center=({}, {}) 半径={} 客户端版本={} 服务端版本={} 已记录区块={}",
                centerX, centerZ, radius, knownRevision, serverRevision,
                data.chunkCount(player.getUUID()));
        if (knownRevision >= 0 && knownRevision == serverRevision) {
            sendUnchanged(player);
            return;
        }

        WorldSurfaceSampler.Snapshot hit = cached(key);
        if (hit != null) {
            /* 命中缓存也不能直接发。缓存里存的是"上一次当场采到的地面"，而玩家走过、
               之后卸载的区块是记在<b>他自己</b>的已探索档案里的——档案一直在长。直接发缓存
               就等于把地图冻在第一次打开的那一刻，这正是"内容不跟着探索变"的直接原因。
               所以补全这一步每次都要现做。 */
            send(player, level, withExplored(player.getUUID(), level, hit));
            return;
        }

        MinecraftServer server = player.getServer();
        UUID playerId = player.getUUID();
        /* 已探索仓库的实例必须在主线程取好——getDataStorage() 不是线程安全的；
           拿到的那份 Data 内部是并发容器，可以放心交给采样线程去合并。 */
        ExploredMapStore.Data explored = ExploredMapStore.data(level);

        CompletableFuture
                .supplyAsync(() -> {
                    WorldSurfaceSampler.Snapshot fresh =
                            WorldSurfaceSampler.sample(level, centerX, centerZ, radius);
                    /* 把这一片按区块并进<b>请求者自己</b>的已探索仓库：他走过、之后卸载的
                       区块，下次再看地图就能从他那份档案里拼回来，而不是一片空白。 */
                    ExploredMapStore.record(playerId, explored, fresh.colors(),
                            fresh.originX(), fresh.originZ(), fresh.size());
                    return fresh;
                }, POOL)
                .thenAccept(snapshot -> {
                    if (snapshot == null) {
                        return;
                    }
                    /* 缓存"纯采样"：补全留到发送前现做。要是把补全结果一起缓存了，
                       下一次命中缓存又会把当时那份结果直接发出去，等于没修。 */
                    cache(key, snapshot);
                    if (server == null) {
                        return;
                    }
                    /* 回主线程发包：PacketDistributor 不是线程安全的，玩家也可能在这一瞬间
                       掉线得重新取一次；withExplored 同样必须在主线程（要碰 getDataStorage）。 */
                    server.execute(() -> {
                        /* 采样的 record() 跑在采样线程上，而 SavedData 的"脏"标记不是 volatile——
                           在那儿设可能延迟到主线程都看不见，那一轮自动保存就把这份改动漏掉了。
                           回主线程再标一次，确保它一定进得去下一次落盘。 */
                        explored.setDirty();
                        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                        if (online != null) {
                            send(online, level, withExplored(playerId, level, snapshot));
                        }
                    });
                })
                .exceptionally(throwable -> {
                    ModMain.LOGGER.error("地图采样失败: {} r={}", key, radius, throwable);
                    return null;
                });
    }

    /**
     * 复制一份采样，用已探索档案补上这次没加载到的格子。
     *
     * <p><b>必须复制再补。</b>缓存里那一份要留给下一次请求继续用，而
     * {@link ExploredMapStore#fillGaps} 是就地改数组的——不复制就把缓存污染了，
     * 下次命中缓存发出去的就成了"上次补全过的样子"，又回到陈旧数据。</p>
     *
     * <p>只能在主线程调用：里面要取 {@code getDataStorage()}。</p>
     */
    private static WorldSurfaceSampler.Snapshot withExplored(UUID owner, ServerLevel level,
                                                             WorldSurfaceSampler.Snapshot snapshot) {
        int[] colors = snapshot.colors().clone();
        ExploredMapStore.fillGaps(owner, ExploredMapStore.data(level), colors,
                snapshot.originX(), snapshot.originZ(), snapshot.size());
        return new WorldSurfaceSampler.Snapshot(
                snapshot.originX(), snapshot.originZ(), snapshot.size(), colors);
    }

    /**
     * 回一个"没有变化"的空片。
     *
     * <p>客户端收到 {@code size = 0} 的片会直接忽略，手上那份数据原样保留
     * （见 {@code WorldMapClientData.accept}）。</p>
     */
    private static void sendUnchanged(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WorldMapDataPacket(
                0, 0, WorldMapDataPacket.NO_CHANGE, 0, 1, 0, new int[0]));
    }

    /** 按片下发。即使只探索了一小块也是整块照发——未探索的格子值是 -1，客户端画成黑色。 */
    private static void send(ServerPlayer player, ServerLevel level,
                             WorldSurfaceSampler.Snapshot snapshot) {
        int revision = ExploredMapStore.data(level).revision(player.getUUID());
        int[] colors = snapshot.colors();
        int chunkTotal = (colors.length + WorldMapDataPacket.CHUNK_INTS - 1)
                / WorldMapDataPacket.CHUNK_INTS;
        if (chunkTotal <= 0) {
            chunkTotal = 1;
        }

        for (int index = 0; index < chunkTotal; index++) {
            int from = index * WorldMapDataPacket.CHUNK_INTS;
            int to = Math.min(colors.length, from + WorldMapDataPacket.CHUNK_INTS);
            /* 最后一片可能为空（长度正好是整数倍时），给个空数组把总数交代清楚 */
            int[] slice = from < to ? Arrays.copyOfRange(colors, from, to) : new int[0];
            PacketDistributor.sendToPlayer(player, new WorldMapDataPacket(
                    snapshot.originX(), snapshot.originZ(), snapshot.size(),
                    index, chunkTotal, revision, slice));
        }
    }

    private static WorldSurfaceSampler.Snapshot cached(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    private static void cache(String key, WorldSurfaceSampler.Snapshot snapshot) {
        synchronized (CACHE) {
            CACHE.put(key, snapshot);
        }
    }
}
