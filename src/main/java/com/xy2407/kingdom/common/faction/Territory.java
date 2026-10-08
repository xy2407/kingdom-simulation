package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 一块领地：隶属某个国家，范围是市政厅所在区块向四周各扩 2 格的 5×5 区块。
 */
public record Territory(String id, String name, String nationId, UUID owner,
                        String dimension, int centerX, int centerZ,
                        int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                        int createdDay) {

    public static final int CHUNK_RADIUS = 2;

    public static final int CHUNK_COUNT = (CHUNK_RADIUS * 2 + 1) * (CHUNK_RADIUS * 2 + 1);

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public boolean containsBlock(double x, double z) {
        return containsChunk((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
    }

    public int chunkSpan() {
        return maxChunkX - minChunkX + 1;
    }
}
