package com.xy2407.kingdom.common.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * 世界表面采样：把一块区域的地表转成地图像素，逐列取最高的有色方块。
 */
public final class WorldSurfaceSampler {

    public static final int UNEXPLORED = -1;

    private static final int NO_SURFACE = Integer.MIN_VALUE;

    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    private WorldSurfaceSampler() {
    }

    public record Snapshot(int originX, int originZ, int size, int[] colors) {

        public int at(int worldX, int worldZ) {
            int x = worldX - originX;
            int z = worldZ - originZ;
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return UNEXPLORED;
            }
            return colors[z * size + x];
        }

        public int exploredCount() {
            int count = 0;
            for (int color : colors) {
                if (color != UNEXPLORED) {
                    count++;
                }
            }
            return count;
        }
    }

    public static Snapshot sample(ServerLevel level, int centerX, int centerZ, int radius) {
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        int[] colors = new int[size * size];
        Arrays.fill(colors, UNEXPLORED);

        int[] heights = new int[size * size];
        Arrays.fill(heights, NO_SURFACE);
        MapColor[] mapColors = new MapColor[size * size];

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < size; dz++) {
            int worldZ = originZ + dz;
            for (int dx = 0; dx < size; dx++) {
                int worldX = originX + dx;
                int index = dz * size + dx;

                ChunkPos chunk = new ChunkPos(worldX >> 4, worldZ >> 4);
                if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    continue;
                }

                int surface = surfaceY(level, cursor, worldX, worldZ);
                if (surface == NO_SURFACE) {
                    continue;
                }

                cursor.set(worldX, surface, worldZ);
                heights[index] = surface;
                mapColors[index] = level.getBlockState(cursor).getMapColor(level, cursor);
            }
        }

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int index = dz * size + dx;
                MapColor mapColor = mapColors[index];
                if (mapColor == null || mapColor == MapColor.NONE) {
                    continue;
                }
                colors[index] = mapColor.getPackedId(
                        brightnessFor(heights, size, dx, dz, heights[index]));
            }
        }

        return new Snapshot(originX, originZ, size, colors);
    }

    public static int[] sampleChunk(ServerLevel level, int chunkX, int chunkZ) {
        int[] colors = new int[CHUNK_CELLS];
        Arrays.fill(colors, UNEXPLORED);
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return colors;
        }

        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int[] heights = new int[CHUNK_CELLS];
        Arrays.fill(heights, NO_SURFACE);
        MapColor[] mapColors = new MapColor[CHUNK_CELLS];

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < CHUNK_SIDE; dz++) {
            for (int dx = 0; dx < CHUNK_SIDE; dx++) {
                int worldX = originX + dx;
                int worldZ = originZ + dz;
                int surface = surfaceY(level, cursor, worldX, worldZ);
                if (surface == NO_SURFACE) {
                    continue;
                }
                int index = dz * CHUNK_SIDE + dx;
                cursor.set(worldX, surface, worldZ);
                heights[index] = surface;
                mapColors[index] = level.getBlockState(cursor).getMapColor(level, cursor);
            }
        }

        for (int dz = 0; dz < CHUNK_SIDE; dz++) {
            for (int dx = 0; dx < CHUNK_SIDE; dx++) {
                int index = dz * CHUNK_SIDE + dx;
                MapColor mapColor = mapColors[index];
                if (mapColor == null || mapColor == MapColor.NONE) {
                    continue;
                }
                colors[index] = mapColor.getPackedId(
                        brightnessFor(heights, CHUNK_SIDE, dx, dz, heights[index]));
            }
        }
        return colors;
    }

    private static MapColor.Brightness brightnessFor(int[] heights, int size,
                                                     int dx, int dz, int height) {
        long sum = 0;
        int count = 0;
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                int neighborX = dx + offsetX;
                int neighborZ = dz + offsetZ;
                if (neighborX < 0 || neighborZ < 0 || neighborX >= size || neighborZ >= size) {
                    continue;
                }
                int neighbor = heights[neighborZ * size + neighborX];
                if (neighbor == NO_SURFACE) {
                    continue;
                }
                sum += neighbor;
                count++;
            }
        }
        if (count == 0) {
            return MapColor.Brightness.NORMAL;
        }
        double average = sum / (double) count;
        if (height > average) {
            return MapColor.Brightness.HIGH;
        }
        if (height < average) {
            return MapColor.Brightness.LOW;
        }
        return MapColor.Brightness.NORMAL;
    }

    private static int surfaceY(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                int worldX, int worldZ) {
        int top = level.getMaxBuildHeight() - 1;
        int bottom = level.getMinBuildHeight();

        int from = top;
        int hinted = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
        if (hinted > bottom && hinted <= top) {
            cursor.set(worldX, hinted, worldZ);
            if (level.getBlockState(cursor).isAir()) {
                cursor.set(worldX, hinted - 1, worldZ);
                if (!level.getBlockState(cursor).isAir()) {
                    from = hinted - 1;
                }
            }
        }

        for (int y = from; y >= bottom; y--) {
            cursor.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (state.getMapColor(level, cursor) != MapColor.NONE) {
                return y;
            }
        }
        return NO_SURFACE;
    }
}
