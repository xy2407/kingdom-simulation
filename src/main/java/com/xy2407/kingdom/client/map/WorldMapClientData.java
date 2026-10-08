package com.xy2407.kingdom.client.map;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapDataPacket;

import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端地图仓库：按区块累积已探索的调色板数据，并算出视口里还缺哪些区块。
 */
@OnlyIn(Dist.CLIENT)
public final class WorldMapClientData {

    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;
    private static final byte EMPTY_CELL = 0;
    private static final long REASK_NANOS = 60_000_000_000L;

    private static final Map<Long, byte[]> CHUNKS = new ConcurrentHashMap<>();
    private static final Map<Long, Long> EMPTY = new ConcurrentHashMap<>();

    private static int size;
    private static int originX;
    private static int originZ;
    private static int[] colors;
    private static int received;
    private static int total = 1;
    private static boolean pending;
    private static long revision;

    private WorldMapClientData() {
    }

    public static void accept(WorldMapDataPacket packet) {
        if (packet.size() <= WorldMapDataPacket.NO_CHANGE) {
            pending = false;
            return;
        }
        if (packet.chunkIndex() <= 0) {
            size = Math.max(0, packet.size());
            originX = packet.originX();
            originZ = packet.originZ();
            colors = size > 0 ? new int[size * size] : null;
            if (colors != null) {
                Arrays.fill(colors, WorldSurfaceSampler.UNEXPLORED);
            }
            received = 0;
        }
        if (colors == null) {
            return;
        }

        total = Math.max(1, packet.chunkTotal());
        int[] slice = packet.colors();
        int from = packet.chunkIndex() * WorldMapDataPacket.CHUNK_INTS;
        if (slice != null && from >= 0 && from < colors.length) {
            int length = Math.min(slice.length, colors.length - from);
            System.arraycopy(slice, 0, colors, from, length);
        }

        received++;
        pending = false;
        if (received >= total) {
            merge(originX, originZ, size, colors);
            revision++;
        }
    }

    private static void merge(int originX, int originZ, int size, int[] colors) {
        long now = System.nanoTime();
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                long id = ChunkPos.asLong(chunkX, chunkZ);
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                byte[] gathered = null;

                for (int z = 0; z < CHUNK_SIDE; z++) {
                    int worldZ = baseZ + z;
                    int dz = worldZ - originZ;
                    if (dz < 0 || dz >= size) {
                        continue;
                    }
                    for (int x = 0; x < CHUNK_SIDE; x++) {
                        int worldX = baseX + x;
                        int dx = worldX - originX;
                        if (dx < 0 || dx >= size) {
                            continue;
                        }
                        int packed = colors[dz * size + dx];
                        if (packed == WorldSurfaceSampler.UNEXPLORED) {
                            continue;
                        }
                        if (gathered == null) {
                            gathered = new byte[CHUNK_CELLS];
                        }
                        gathered[z * CHUNK_SIDE + x] = (byte) packed;
                    }
                }

                if (gathered == null) {
                    if (!CHUNKS.containsKey(id)) {
                        EMPTY.put(id, now);
                    }
                    continue;
                }
                lighten(id, gathered);
                EMPTY.remove(id);
            }
        }
    }

    private static void lighten(long id, byte[] gathered) {
        byte[] target = CHUNKS.computeIfAbsent(id, key -> new byte[CHUNK_CELLS]);
        synchronized (target) {
            for (int i = 0; i < CHUNK_CELLS; i++) {
                if (gathered[i] != EMPTY_CELL) {
                    target[i] = gathered[i];
                }
            }
        }
    }

    public static int[] missingRect(int viewOriginX, int viewOriginZ, int viewSize) {
        long now = System.nanoTime();
        int minChunkX = viewOriginX >> 4;
        int maxChunkX = (viewOriginX + viewSize - 1) >> 4;
        int minChunkZ = viewOriginZ >> 4;
        int maxChunkZ = (viewOriginZ + viewSize - 1) >> 4;

        int missMinX = Integer.MAX_VALUE;
        int missMinZ = Integer.MAX_VALUE;
        int missMaxX = Integer.MIN_VALUE;
        int missMaxZ = Integer.MIN_VALUE;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (alreadyHave(chunkX, chunkZ, now)) {
                    continue;
                }
                missMinX = Math.min(missMinX, chunkX);
                missMaxX = Math.max(missMaxX, chunkX);
                missMinZ = Math.min(missMinZ, chunkZ);
                missMaxZ = Math.max(missMaxZ, chunkZ);
            }
        }
        if (missMinX > missMaxX) {
            return null;
        }
        int originX = missMinX << 4;
        int originZ = missMinZ << 4;
        int width = (missMaxX - missMinX + 1) << 4;
        int depth = (missMaxZ - missMinZ + 1) << 4;
        return new int[] {originX, originZ, Math.max(width, depth)};
    }

    private static boolean alreadyHave(int chunkX, int chunkZ, long now) {
        long id = ChunkPos.asLong(chunkX, chunkZ);
        if (CHUNKS.containsKey(id)) {
            return true;
        }
        Long askedAt = EMPTY.get(id);
        if (askedAt == null) {
            return false;
        }
        if (now - askedAt < REASK_NANOS) {
            return true;
        }
        EMPTY.remove(id);
        return false;
    }

    public static boolean hasAnyChunk() {
        return !CHUNKS.isEmpty();
    }

    public static int chunkCount() {
        return CHUNKS.size();
    }

    public static long revision() {
        return revision;
    }

    public static boolean isPending() {
        return pending;
    }

    public static void markPending() {
        pending = true;
    }

    public static int packedAt(int worldX, int worldZ) {
        byte[] cell = CHUNKS.get(ChunkPos.asLong(worldX >> 4, worldZ >> 4));
        if (cell == null) {
            return WorldSurfaceSampler.UNEXPLORED;
        }
        int value = cell[(worldZ & 15) * CHUNK_SIDE + (worldX & 15)] & 0xFF;
        return value == EMPTY_CELL ? WorldSurfaceSampler.UNEXPLORED : value;
    }

    public static void clear() {
        CHUNKS.clear();
        EMPTY.clear();
        colors = null;
        size = 0;
        originX = 0;
        originZ = 0;
        received = 0;
        total = 1;
        pending = false;
        revision++;
    }
}
