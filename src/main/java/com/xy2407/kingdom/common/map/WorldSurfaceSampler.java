package com.xy2407.kingdom.common.map;

import com.xy2407.kingdom.common.path.ChunkSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * 世界表面采样：把一块区域的地表转成地图像素，逐列取最高的有色方块。
 */
public final class WorldSurfaceSampler {

    public static final int UNEXPLORED = -1;

    public static final int NO_WALKABLE = Integer.MIN_VALUE;

    private static final int NO_SURFACE = Integer.MIN_VALUE;

    private static final int MAX_STEP_HEIGHT = 1;

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

    public static Snapshot sample(Level level, int centerX, int centerZ, int radius) {
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
                ChunkPos chunk = new ChunkPos(worldX >> 4, worldZ >> 4);
                if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    continue;
                }
                int surface = surfaceY(level, cursor, worldX, worldZ);
                if (surface == NO_SURFACE) {
                    continue;
                }
                int index = dz * size + dx;
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

    public static int[] sampleChunk(Level level, int chunkX, int chunkZ) {
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

    // 道路面的可行走采样（P0 对齐，D1 已定）：判定完全复用寻路快照的谓词语义——
    // ① 脚下方块 isWalkableFloor（含楼梯/半砖/脚手架，排除栅栏/墙）；
    // ② 站立位及其上 1 格 isPassable（净空 2，含门/地毯/压力板，排除火与细雪）——
    //    与 ChunkSnapshot 的 standable 层逐方块等价，保证道路 y 必然是快照可站的层；
    // ③ 相邻参考高度 ±1（NPC 步高）；列内无满足者返回 NO_WALKABLE（调用方跳过该格且不断线）。
    // refY 传 NO_WALKABLE 表示无相邻参考（起笔/传送落点）。
    public static int walkableSurfaceY(Level level, int worldX, int worldZ, int refY) {
        int top = Math.min(level.getMaxBuildHeight() - 1,
                level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ));
        int bottom = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top; y > bottom; y--) {
            if (!isStandableFloor(level, cursor, worldX, y, worldZ)) {
                continue;
            }
            if (refY != NO_WALKABLE && Math.abs(y - refY) > MAX_STEP_HEIGHT) {
                continue;
            }
            return y;
        }
        return NO_WALKABLE;
    }

    // 落脚格判定：站立位上方 2 格净空（上升边与平走一致，不再多加第 3 格）
    private static boolean isStandableFloor(Level level, BlockPos.MutableBlockPos cursor,
                                            int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return false;
        }
        cursor.set(x, y, z);
        if (!ChunkSnapshot.isWalkableFloor(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 1, z);
        if (!ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 2, z);
        return ChunkSnapshot.isPassable(level.getBlockState(cursor));
    }

    private static boolean hasCollision(Level level, BlockPos.MutableBlockPos cursor,
                                        int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() - 1) {
            return false;
        }
        cursor.set(x, y, z);
        BlockState state = level.getBlockState(cursor);
        return !state.isAir() && !state.getCollisionShape(level, cursor).isEmpty();
    }

    private static int surfaceY(Level level, BlockPos.MutableBlockPos cursor,
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
