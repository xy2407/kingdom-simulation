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
 */
public final class WorldMapServer {

    private static final int WORKERS = 2;

    private static final int CACHE_LIMIT = 64;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "kingdom-map-sampler");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<String, WorldSurfaceSampler.Snapshot> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WorldSurfaceSampler.Snapshot> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private WorldMapServer() {
    }

    public static void reset() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static void fulfill(ServerPlayer player, int centerX, int centerZ, int radius,
                               int knownRevision) {
        ServerLevel level = player.serverLevel();
        PlayerProfileStore.INSTANCE.setMapCenter(player.getUUID(), centerX, centerZ);

        String dimension = level.dimension().location().toString();
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        String key = dimension + "|" + originX + "|" + originZ + "|" + size;

        ExploredMapStore.Data data = ExploredMapStore.data(level);
        int serverRevision = data.revision(player.getUUID());
        ModMain.LOGGER.debug("[地图] 请求 center=({}, {}) 半径={} 客户端版本={} 服务端版本={} 已记录区块={}",
                centerX, centerZ, radius, knownRevision, serverRevision,
                data.chunkCount(player.getUUID()));
        if (knownRevision >= 0 && knownRevision == serverRevision) {
            sendUnchanged(player);
            return;
        }

        WorldSurfaceSampler.Snapshot hit = cached(key);
        if (hit != null) {
            send(player, level, withExplored(player.getUUID(), level, hit));
            return;
        }

        MinecraftServer server = player.getServer();
        UUID playerId = player.getUUID();
        ExploredMapStore.Data explored = ExploredMapStore.data(level);

        CompletableFuture
                .supplyAsync(() -> {
                    WorldSurfaceSampler.Snapshot fresh =
                            WorldSurfaceSampler.sample(level, centerX, centerZ, radius);
                    ExploredMapStore.record(playerId, explored, fresh.colors(),
                            fresh.originX(), fresh.originZ(), fresh.size());
                    return fresh;
                }, POOL)
                .thenAccept(snapshot -> {
                    if (snapshot == null) {
                        return;
                    }
                    cache(key, snapshot);
                    if (server == null) {
                        return;
                    }
                    server.execute(() -> {
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

    private static WorldSurfaceSampler.Snapshot withExplored(UUID owner, ServerLevel level,
                                                             WorldSurfaceSampler.Snapshot snapshot) {
        int[] colors = snapshot.colors().clone();
        ExploredMapStore.fillGaps(owner, ExploredMapStore.data(level), colors,
                snapshot.originX(), snapshot.originZ(), snapshot.size());
        return new WorldSurfaceSampler.Snapshot(
                snapshot.originX(), snapshot.originZ(), snapshot.size(), colors);
    }

    private static void sendUnchanged(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WorldMapDataPacket(
                0, 0, WorldMapDataPacket.NO_CHANGE, 0, 1, 0, new int[0]));
    }

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
