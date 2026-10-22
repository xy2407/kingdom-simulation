package com.xy2407.kingdom.common.map;

import com.xy2407.kingdom.common.path.ChunkSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * 世界表面采样：逐列取地表色与高度，颜色存无光照基色，光照由渲染层按视口分辨率现算。
 */
public final class WorldSurfaceSampler {

    public static final int UNEXPLORED = 0;

    public static final int NO_WALKABLE = Integer.MIN_VALUE;

    public static final int NO_SURFACE = Integer.MIN_VALUE;

    private static final int MAX_STEP_HEIGHT = 1;

    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    private WorldSurfaceSampler() {
    }

    public record Snapshot(int originX, int originZ, int size, int[] colors, int[] heights) {

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

    public record SampledChunk(int[] colors, int[] heights) {
    }

    public static Snapshot sample(Level level, int centerX, int centerZ, int radius) {
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        int[] colors = new int[size * size];
        int[] heights = new int[size * size];
        Arrays.fill(heights, NO_SURFACE);

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
                BlockState state = level.getBlockState(cursor);
                if (state.getMapColor(level, cursor) == MapColor.NONE) {
                    continue;
                }
                heights[index] = surface;
                colors[index] = baseArgb(state.getMapColor(level, cursor));
            }
        }

        return new Snapshot(originX, originZ, size, colors, heights);
    }

    public static SampledChunk sampleChunk(Level level, int chunkX, int chunkZ) {
        int[] colors = new int[CHUNK_CELLS];
        int[] heights = new int[CHUNK_CELLS];
        Arrays.fill(heights, NO_SURFACE);
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return new SampledChunk(colors, heights);
        }

        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
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
                BlockState state = level.getBlockState(cursor);
                MapColor mapColor = state.getMapColor(level, cursor);
                if (mapColor == MapColor.NONE) {
                    continue;
                }
                heights[index] = surface;
                colors[index] = baseArgb(mapColor);
            }
        }
        return new SampledChunk(colors, heights);
    }

    // 地貌基色：MapColor 的 NORMAL 档，无任何光照（光照在渲染层按视口分辨率现算）
    private static int baseArgb(MapColor mapColor) {
        int abgr = MapColor.getColorFromPackedId(mapColor.getPackedId(MapColor.Brightness.NORMAL));
        int red = abgr & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
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
            if (state.isAir() || isGroundPlant(state)) {
                continue;
            }
            if (state.getMapColor(level, cursor) != MapColor.NONE) {
                return y;
            }
        }
        return NO_SURFACE;
    }

    // 覆盖在地表上的草类植物不采样（Xaero 同款做法），花朵/树叶/作物照常出图
    private static boolean isGroundPlant(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH);
    }
}
