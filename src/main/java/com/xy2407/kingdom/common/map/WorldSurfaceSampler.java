package com.xy2407.kingdom.common.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * 世界表面采样：把一块区域的地表转成地图像素。
 *
 * <p>做法对照原版 {@code MapItemSavedData.updateColor()}：逐列取最高的实体方块，
 * 用它的 {@link MapColor} 压成地图调色板里的一个字节，并按高度分亮度档做出起伏感。
 * 这样出来的是**世界真实数据**，不是画上去的。</p>
 *
 * <p>未加载的区块记 {@link #UNEXPLORED}（0）。原版地图里 0 就表示未探索，
 * 渲染时画成黑色。</p>
 *
 * <p>本类只依赖服务端世界，不碰任何客户端类型，预览与联机都用同一份结果。</p>
 */
public final class WorldSurfaceSampler {

    /** 原版地图的「未探索」值，也是 colors 数组的初始值。 */
    public static final byte UNEXPLORED = 0;

    private WorldSurfaceSampler() {
    }

    /**
     * 一次采样的结果。
     *
     * <p>{@code colors} 是 {@code size × size} 的一维数组，行优先：
     * 下标 = {@code z * size + x}，与 (originX, originZ) 起算。</p>
     *
     * <p>注意：存的是 byte，但打包 id 的值域是 0~255，取用时务必
     * {@code & 0xFF} 转成无符号，否则大于 127 的值会变成负数。</p>
     *
     * @param originX  左上角（最小 X）的世界坐标
     * @param originZ  左上角（最小 Z）的世界坐标
     * @param size     边长，等于 半径 × 2 + 1
     * @param colors   地图调色板的打包 id，0 表示未探索
     */
    public record Snapshot(int originX, int originZ, int size, byte[] colors) {

        /** 取某个世界坐标的像素值；超出范围返回未探索。 */
        public byte at(int worldX, int worldZ) {
            int x = worldX - originX;
            int z = worldZ - originZ;
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return UNEXPLORED;
            }
            return colors[z * size + x];
        }

        /** 取某个世界坐标的打包 id，已按无符号展开到 0~255。 */
        public int packedAt(int worldX, int worldZ) {
            return at(worldX, worldZ) & 0xFF;
        }

        /** 已探索的格子数，用来判断这张图有没有内容。 */
        public int exploredCount() {
            int count = 0;
            for (byte color : colors) {
                if (color != UNEXPLORED) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 以 (centerX, centerZ) 为中心采样一个正方形区域。
     *
     * @param radius 半径，单位是方块；结果边长 = radius × 2 + 1
     */
    public static Snapshot sample(ServerLevel level, int centerX, int centerZ, int radius) {
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        byte[] colors = new byte[size * size];

        int seaLevel = level.getSeaLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < size; dz++) {
            int worldZ = originZ + dz;
            for (int dx = 0; dx < size; dx++) {
                int worldX = originX + dx;
                int index = dz * size + dx;

                /* 区块没加载就没数据可比，按未探索处理。
                   这也是原版地图的行为：没走过的地方一直是 0。 */
                ChunkPos chunk = new ChunkPos(worldX >> 4, worldZ >> 4);
                if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    colors[index] = UNEXPLORED;
                    continue;
                }

                colors[index] = sampleColumn(level, cursor, worldX, worldZ, seaLevel);
            }
        }

        return new Snapshot(originX, originZ, size, colors);
    }

    /**
     * 采一列。
     *
     * <p>{@code getHeight(WORLD_SURFACE)} 给的是「第一个空气方块」的高度，
     * 所以实体方块在它下面一格。</p>
     */
    private static byte sampleColumn(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                     int worldX, int worldZ, int seaLevel) {
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
        int bottom = level.getMinBuildHeight();

        /* 从地表往下退，跳过空气（水面、树的空隙等），找到真正的实体方块。 */
        int y = Math.min(surface - 1, level.getMaxBuildHeight() - 1);
        BlockState state = null;
        while (y > bottom) {
            cursor.set(worldX, y, worldZ);
            BlockState current = level.getBlockState(cursor);
            if (!current.isAir()) {
                state = current;
                break;
            }
            y--;
        }
        if (state == null) {
            return UNEXPLORED;
        }

        MapColor mapColor = state.getMapColor(level, cursor);
        return mapColor.getPackedId(brightnessFor(y, seaLevel));
    }

    /**
     * 按高度分亮度档，让山脊亮、洼地暗，跟原版地图那种起伏感一致。
     * 原版是按相邻列的高度差算，这里用相对海平面的高差，效果接近而便宜得多。
     */
    private static MapColor.Brightness brightnessFor(int y, int seaLevel) {
        /* 原版四档的亮度值：LOWEST 135 / LOW 180 / NORMAL 220 / HIGH 255。
           注意没有 HIGHEST，最高一档就是 HIGH。 */
        int above = y - seaLevel;
        if (above < 0) {
            return MapColor.Brightness.LOWEST;   // 海平面以下，最暗
        }
        if (above < 24) {
            return MapColor.Brightness.LOW;
        }
        if (above < 56) {
            return MapColor.Brightness.NORMAL;
        }
        return MapColor.Brightness.HIGH;         // 高处最亮
    }
}
