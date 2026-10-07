package com.xy2407.kingdom.common.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * 世界表面采样：把一块区域的地表转成地图像素。
 *
 * <p>逐列取<b>最高的实体方块</b>，用它的 {@link MapColor} 压成地图调色板里的一个字节。
 * 出来的是**世界真实数据**，不是画上去的。</p>
 *
 * <p><b>不做明暗</b>。原版 {@code MapItemSavedData.updateColor()} 还会拿本列高度和四个对角邻居
 * 比高低、定 {@code HIGH}/{@code NORMAL}/{@code LOW} 三档来表现起伏；本实现**去掉那一步**，
 * 所有格子统一用 {@link #FLAT_BRIGHTNESS}（系数 1.0，即不做任何调整）——
 * 地图上看到的就是**方块自身的颜色**，没有阴影。代价是失去高度起伏的视觉提示
 * （山脊不再发亮、洼地不再压暗）。</p>
 *
 * <p>未加载的区块记 {@link #UNEXPLORED}（-1），渲染时画成黑色——与原版地图一致
 * （原版 {@code MapColor.NONE} 的打包 id 是 0，画出来同样是黑）。</p>
 *
 * <p><b>为什么不用 {@code Heightmap.WORLD_SURFACE} 定起点。</b>那张高度图是**异步维护**的：
 * {@code hasChunk} 为真只说明区块在，不说明高度图算好了。重进世界、刚加载区块、
 * 或者区块刚被外部改动时，{@code getHeight} 常常给出偏低的值甚至最低值——
 * 从那个位置往下扫，整列地表就被跳过了：高层地形（树冠、建筑、山脊）全部漏掉，
 * 采到的是地下的石头之类，地图上表现为"地形被剔除 + 颜色诡异"。
 * 所以这里固定**从世界可建造高度的顶部往下扫**，只认扫到的真实方块。</p>
 *
 * <p>本类只依赖服务端世界，不碰任何客户端类型。</p>
 */
public final class WorldSurfaceSampler {

    /**
     * 「未探索」哨兵值，也是数组的初值。
     *
     * <p>用 -1 而不是 0：0 是 {@code MapColor.NONE} 的打包 id，
     * 虽然语义上也是"没有颜色"，但要跟"采到了地表、颜色为 NONE"区分开。</p>
     */
    public static final int UNEXPLORED = -1;

    /** 表示"这一列没采到地表"。 */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

    /** 区块边长（方块）。逐块采样的数组长度是它的平方。 */
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

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
     * @param originX 左上角（最小 X）的世界坐标
     * @param originZ 左上角（最小 Z）的世界坐标
     * @param size    边长，等于 半径 × 2 + 1
     * @param colors  地图调色板的打包 id，0 表示未探索
     */
    public record Snapshot(int originX, int originZ, int size, int[] colors) {

        /**
         * 取某个世界坐标的调色板打包 id；超出范围返回 {@link #UNEXPLORED}。
         *
         * <p>这个值交给 {@code MapColor.getColorFromPackedId} 就能得到 ARGB，
         * 与 {@link #UNEXPLORED} 比较即可判未探索。</p>
         */
        public int at(int worldX, int worldZ) {
            int x = worldX - originX;
            int z = worldZ - originZ;
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return UNEXPLORED;
            }
            return colors[z * size + x];
        }

        /** 已探索的格子数，用来判断这张图有没有内容。 */
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

    /**
     * 以 (centerX, centerZ) 为中心采样一个正方形区域。
     *
     * @param radius 半径，单位是方块；结果边长 = radius × 2 + 1
     */
    public static Snapshot sample(ServerLevel level, int centerX, int centerZ, int radius) {
        int size = radius * 2 + 1;
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        int[] colors = new int[size * size];
        /* int 数组默认填 0，而 0 是 MapColor.NONE 的打包 id（也是"未探索"的语义），
           但为了跟"采到了地表但颜色是 NONE"区分开，未探索仍显式用 -1 */
        Arrays.fill(colors, UNEXPLORED);

        /* 先只采"高度 + 方块色"，明暗档留到第二遍 —— 原版地图的亮度要拿本列高度
           跟四邻比，不把整片高度采完就不知道邻居多高。 */
        int[] heights = new int[size * size];
        Arrays.fill(heights, NO_SURFACE);
        MapColor[] mapColors = new MapColor[size * size];

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < size; dz++) {
            int worldZ = originZ + dz;
            for (int dx = 0; dx < size; dx++) {
                int worldX = originX + dx;
                int index = dz * size + dx;

                /* 区块没加载就没数据可比，按未探索处理。
                   这也是原版地图的行为：没走过的地方一直是 0。
                   注意这条判断也是**异步采样唯一的安全性来源**——只碰已加载的区块。 */
                ChunkPos chunk = new ChunkPos(worldX >> 4, worldZ >> 4);
                if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    continue;
                }

                int surface = surfaceY(level, cursor, worldX, worldZ);
                if (surface == NO_SURFACE) {
                    continue; // 整列没有"有颜色"的方块，保持 UNEXPLORED
                }

                cursor.set(worldX, surface, worldZ);
                heights[index] = surface;
                mapColors[index] = level.getBlockState(cursor).getMapColor(level, cursor);
            }
        }

        /* 第二遍：照原版地图定明暗档。山脊比四周高就提亮、洼地比四周低就压暗，
           地图因此有起伏。这一步是"看起来像地图"的关键——之前写死成"不调整"，
           整张图平得像贴纸。 */
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

    /**
     * 采<b>一个区块</b>（16×16）的地表，供「已探索地图」逐块持久化。
     *
     * <p>与 {@link #sample} 只有两点不同：范围固定为一个区块；明暗档只看本区块内的邻居
     * ——跨区块的高度得等邻居区块也采过才拿得到，为它把采样拆得更碎不划算。区块边缘那一格的
     * 明暗因此可能与区域采样差一档，拼进整张图看不出来。</p>
     *
     * <p>区块没加载时整块返回 {@link #UNEXPLORED}（调用方据此跳过，不必入库）。</p>
     *
     * @return 长度 256 的打包 id，行优先（{@code z * 16 + x}）
     */
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

    /**
     * 照原版地图的规则选明暗档：拿本列高度跟周围八格比。
     *
     * <p>比四周高 → {@link MapColor.Brightness#HIGH}（提亮），比四周低 →
     * {@link MapColor.Brightness#LOW}（压暗），差不多就
     * {@link MapColor.Brightness#NORMAL}。原版就是靠这一档表现地形起伏的。</p>
     *
     * <p>只比"高度"，不做任何光照/群系计算——这正是原版地图的做法，
     * 颜色也正因此对所有方块都成立（模组方块只要给了 {@code mapColor} 就行）。</p>
     */
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

    /**
     * 扫一列，返回<b>最高的那个「有颜色」的方块</b>的 Y；找不到就返回 {@link #NO_SURFACE}。
     *
     * <p><b>为什么不是"最高的实体方块"。</b>花盆、火把、拉杆、钟这一类方块没有声明
     * {@code mapColor}，取色拿到的是 {@link MapColor#NONE}——照原样写进地图就是一个个黑方块，
     * 像地图上破了洞。它们本来也不代表这格的地形，压着的那层地面才是。所以这里把它们一并跳过，
     * 继续往下找第一个有颜色的方块。</p>
     *
     * <p>判据是"<b>有没有 mapColor</b>"，不是"形状完不完整"。雪层、草丛、睡莲同样是"非完整方块"，
     * 但各自有正确的颜色（雪白、草绿），按完整度去跳会把它们一起丢掉，地图反而失真。</p>
     *
     * <p><b>快速路径与慢速路径。</b>默认从 {@code getMaxBuildHeight() - 1}（世界可建造高度顶部）
     * 一路往下扫，结果与高度图无关，最稳但最贵。</p>
     *
     * <p>高度图可信时可以省掉那段高空扫描：判据是"它那一格是空气、它下面一格是实体"，
     * 即高度图正好指出了地表之上第一格。高度图未就绪或偏低时，
     * <b>它给的位置必然处在地下</b>（那一格就是实体、不是空气），判定立刻失败，
     * 于是退回从顶部扫——正是靠这一点保证"偏低的高度图不会让我们从地底开始采"。</p>
     */
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
                    from = hinted - 1; // 可信：直接从这里起扫
                }
            }
        }

        for (int y = from; y >= bottom; y--) {
            cursor.set(worldX, y, worldZ);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            /* 没有颜色的方块不参与取色（花盆/火把/拉杆…），继续往下找它压着的地面。
               整列都没有有颜色的方块，才返回 NO_SURFACE——那一格保持未探索。 */
            if (state.getMapColor(level, cursor) != MapColor.NONE) {
                return y;
            }
        }
        return NO_SURFACE;
    }
}
