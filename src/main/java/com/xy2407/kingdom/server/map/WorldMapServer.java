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
 * （每条约 145 KB，共约 9 MB）。同一块区域反复看——开界面、拖回来、缩放——都命中缓存。</p>
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
     * 采样以 (centerX, centerZ) 为中心的区域并按 {@link WorldMapDataPacket#CHUNK_BYTES}
     * 切片下发。命中缓存就直接发，不重复采样。
     */
    public static void fulfill(ServerPlayer player, int centerX, int centerZ, int radius) {
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

        WorldSurfaceSampler.Snapshot hit = cached(key);
        if (hit != null) {
            send(player, hit);
            return;
        }

        MinecraftServer server = player.getServer();
        UUID playerId = player.getUUID();

        CompletableFuture
                .supplyAsync(() -> WorldSurfaceSampler.sample(level, centerX, centerZ, radius), POOL)
                .thenAccept(snapshot -> {
                    if (snapshot == null) {
                        return;
                    }
                    cache(key, snapshot);
                    if (server == null) {
                        return;
                    }
                    /* 回主线程发包：PacketDistributor 不是线程安全的，
                       而且玩家可能在这一瞬间掉线，得重新取一次。 */
                    server.execute(() -> {
                        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                        if (online != null) {
                            send(online, snapshot);
                        }
                    });
                })
                .exceptionally(throwable -> {
                    ModMain.LOGGER.error("地图采样失败: {} r={}", key, radius, throwable);
                    return null;
                });
    }

    /** 按片下发。即使只探索了一小块也是整块照发——未探索的格子值就是 0，画成黑色。 */
    private static void send(ServerPlayer player, WorldSurfaceSampler.Snapshot snapshot) {
        byte[] colors = snapshot.colors();
        int chunkTotal = (colors.length + WorldMapDataPacket.CHUNK_BYTES - 1)
                / WorldMapDataPacket.CHUNK_BYTES;
        if (chunkTotal <= 0) {
            chunkTotal = 1;
        }

        for (int index = 0; index < chunkTotal; index++) {
            int from = index * WorldMapDataPacket.CHUNK_BYTES;
            int to = Math.min(colors.length, from + WorldMapDataPacket.CHUNK_BYTES);
            /* 最后一片可能为空（colors 长度正好是整数倍时），给个空数组把总数交代清楚 */
            byte[] slice = from < to ? Arrays.copyOfRange(colors, from, to) : new byte[0];
            PacketDistributor.sendToPlayer(player, new WorldMapDataPacket(
                    snapshot.originX(), snapshot.originZ(), snapshot.size(), index, chunkTotal, slice));
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
