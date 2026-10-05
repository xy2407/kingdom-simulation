package com.xy2407.kingdom.common.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

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
 * <p>未加载的区块记 {@link #UNEXPLORED}（0），渲染时画成黑色——与原版地图一致
 * （原版 {@code MapColor.NONE} 的打包 id 也是 0，语义正好重合）。</p>
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

    /** 原版地图的「未探索」值，也是 colors 数组的初始值。 */
    public static final byte UNEXPLORED = 0;

    /** 表示"这一列没采到地表"。 */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

    /**
     * 纯色取样用的亮度档。
     *
     * <p>原版三档的采样系数是 {@code LOW 0.71 / NORMAL 0.86 / HIGH 1.0}，
     * 只有 <b>HIGH 是"不调整"</b>，拿到的正好是方块自身的颜色；
     * 用 NORMAL 会让整张图统一暗 14%。</p>
     *
     * <p>注意这里的 HIGH 只是借用"系数 1.0"这个性质，<b>不是</b>原版那种
     * "本列比四邻高所以发亮"的意思——那种逐列比高低的明暗已经整个去掉了。</p>
     */
    private static final MapColor.Brightness FLAT_BRIGHTNESS = MapColor.Brightness.HIGH;

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
                    colors[index] = UNEXPLORED;
                    continue;
                }

                int surface = surfaceY(level, cursor, worldX, worldZ);
                if (surface == NO_SURFACE) {
                    continue; // 整列没有实体方块，保持 UNEXPLORED
                }

                cursor.set(worldX, surface, worldZ);
                MapColor mapColor = level.getBlockState(cursor).getMapColor(level, cursor);
                /* 纯色取样：统一用"系数 1.0"那一档，不带任何明暗调整。 */
                colors[index] = mapColor.getPackedId(FLAT_BRIGHTNESS);
            }
        }

        return new Snapshot(originX, originZ, size, colors);
    }

    /**
     * 扫一列，返回<b>最高那个实体方块</b>的 Y；整列没有实体方块时返回 {@link #NO_SURFACE}。
     *
     * <p><b>快速路径与慢速路径。</b>默认从 {@code getMaxBuildHeight() - 1}（世界可建造高度顶部）
     * 一路往下找第一个非空气方块，结果与高度图无关，最稳但最贵。</p>
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
            if (!level.getBlockState(cursor).isAir()) {
                return y;
            }
        }
        return NO_SURFACE;
    }
}
