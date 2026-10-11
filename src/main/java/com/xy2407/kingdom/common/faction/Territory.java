package com.xy2407.kingdom.common.faction;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一块领地：隶属某个国家，范围是任意形状的区块集合，可逐块扩张
 */
public record Territory(String id, String name, String nationId, UUID owner,
                        String dimension, int centerX, int centerZ,
                        int createdDay, Set<Long> chunks) {

    public static final int CHUNK_RADIUS = 2;

    public Territory {
        chunks = chunks == null ? Set.of() : Set.copyOf(chunks);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    public static Set<Long> rectChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        Set<Long> result = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                result.add(chunkKey(chunkX, chunkZ));
            }
        }
        return result;
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunks.contains(chunkKey(chunkX, chunkZ));
    }

    public boolean containsBlock(double x, double z) {
        return containsChunk((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
    }

    public int chunkCount() {
        return chunks.size();
    }
}
