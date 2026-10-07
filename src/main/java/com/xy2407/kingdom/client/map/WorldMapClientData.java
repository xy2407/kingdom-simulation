package com.xy2407.kingdom.client.map;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapDataPacket;

import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端的地图数据缓存：<b>按区块累积</b>，画过的地方就一直留着。
 *
 * <h2>为什么要累积，而不是"存当前这一块"</h2>
 * 早先这里是"一次一块"——服务端发来一整块，就整块覆盖掉上一块。那带来两个毛病：
 * 地图的范围被"当前请求的那块"框死，且<b>刚画过的区域下一次进视口还得重传一遍</b>。
 *
 * <p>现在改成按区块攒：每收到一整块，就把它切成一格格的区块<b>并进仓库</b>；
 * 绘制时按世界坐标直接查仓库（{@link #packedAt}）。于是拖动只是换一块地方查，
 * 走过的地方永远都在，不需要为已经有的内容再发一次请求。</p>
 *
 * <h2>请求这一侧配套</h2>
 * {@link #missingRect} 负责算"视口里我还没有、且值得再问一次"的区块的外接矩形，
 * 元素只请求这一块。玩家视野里已经有的部分一个字节都不会再走网。
 *
 * <h2>两个集合</h2>
 * <ul>
 *   <li>{@link #CHUNKS}——<b>有数据</b>的区块（哪怕只有一格采到了）。</li>
 *   <li>{@link #EMPTY}——问过服务端、<b>那边也没有记录</b>的区块，记下询问时刻。
 *       过 {@link #REASK_NANOS} 之后会再问一次：玩家可能刚走到那边，服务端的档案
 *       这期间长出来了。没有这一层，未探索区域会被反复请求；只有这一层而没有过期，
 *       新探索又永远补不进来。</li>
 * </ul>
 *
 * <p>权威数据在服务端（按玩家 UUID 持久化的那份档案）。这里只是显示缓存，退出即失，
 * 下次进游戏重新拉——所以不需要在客户端再落一份盘。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class WorldMapClientData {

    /** 每区块的格子数，与服务端一致。 */
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    /** 0 表示"这一格没有记录"（也正好是 MapColor.NONE 的打包 id，两者在地图上都是黑）。 */
    private static final byte EMPTY_CELL = 0;

    /** 未探索区块隔多久再问一次。一分钟足够跟上"玩家刚走过去"的节奏，又不会反复白问。 */
    private static final long REASK_NANOS = 60_000_000_000L;

    /** 有数据的区块。key = {@code ChunkPos.asLong}。 */
    private static final Map<Long, byte[]> CHUNKS = new ConcurrentHashMap<>();

    /** 问过、但服务端也没有记录的区块 → 询问时刻（纳秒）。 */
    private static final Map<Long, Long> EMPTY = new ConcurrentHashMap<>();

    /* ---- 下面这组是"正在拼装的那一整块"，服务端仍按整块分片下发 ---- */

    private static int size;
    private static int originX;
    private static int originZ;
    private static int[] colors;
    private static int received;
    private static int total = 1;

    /** 拼装中的那一块已收到的片数。 */
    private static boolean pending;

    /** 每次有新内容并进仓库就自增，绘制方据此判断要不要重建像素表。 */
    private static long revision;

    private WorldMapClientData() {
    }

    public static void accept(WorldMapDataPacket packet) {
        if (packet.size() <= WorldMapDataPacket.NO_CHANGE) {
            /* "没有变化"空片：手上这份已经是最新的，什么都不动 */
            pending = false;
            return;
        }
        if (packet.chunkIndex() <= 0) {
            /* 第一片：重开拼装缓冲区 */
            size = Math.max(0, packet.size());
            originX = packet.originX();
            originZ = packet.originZ();
            colors = size > 0 ? new int[size * size] : null;
            if (colors != null) {
                /* 未探索必须显式标 -1：数组初值 0 是 MapColor.NONE 的打包 id，
                   与"这一格没采到"要区分开 */
                java.util.Arrays.fill(colors, WorldSurfaceSampler.UNEXPLORED);
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

    /**
     * 把刚拼好的一整块切成一格格区块并进仓库。
     *
     * <p>有数据的区块并入（只覆盖这次真采到的格子）；整块都没数据的区块记进
     * {@link #EMPTY}，免得同一片未探索区被反复请求。</p>
     */
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
                    /* 整块空白：记下"问过了"，这段时间不再重复问 */
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

    /**
     * 把新采到的格子并在已有区块上——<b>只覆盖这次真采到的</b>，其余保持原样。
     *
     * <p>不能整块替换：区块处在这块的边缘时，只有一部分落在区域内，整块替换会把
     * 上一轮采到的另一半擦成空白。</p>
     */
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

    /**
     * 算出"视口里我还没有、且值得再问一次"的区块的<b>外接矩形</b>。
     *
     * <p>取外接矩形（而不是逐块精确列举）是有意的：请求协议本来就是"中心 + 半径"，
     * 而缺的区块通常正是拖动新露出来的那条边，外接矩形与它几乎重合，多带的几个区块
     * 在下一次并入后也就补上了。</p>
     *
     * @return {@code {originX, originZ, side}}（正方形边长，便于换算成服务端的半径），
     *         视口内什么都不缺时返回 {@code null}
     */
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

    /** 这一块算不算"不用问了"：有数据，或者刚问过而那边也没有。 */
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
        /* 过期了就再问一次：玩家可能刚走到那片，服务端档案这时才有内容 */
        EMPTY.remove(id);
        return false;
    }

    /** 仓库里有没有任何内容。没有时绘制方不必铺像素。 */
    public static boolean hasAnyChunk() {
        return !CHUNKS.isEmpty();
    }

    /** 记住的区块数，诊断用。 */
    public static int chunkCount() {
        return CHUNKS.size();
    }

    /** 每次并入新内容自增。绘制方用它判断是否需要重建。 */
    public static long revision() {
        return revision;
    }

    public static boolean isPending() {
        return pending;
    }

    public static void markPending() {
        pending = true;
    }

    /**
     * 取某个世界坐标的调色板打包 id；没有记录返回 {@link WorldSurfaceSampler#UNEXPLORED}。
     */
    public static int packedAt(int worldX, int worldZ) {
        byte[] cell = CHUNKS.get(ChunkPos.asLong(worldX >> 4, worldZ >> 4));
        if (cell == null) {
            return WorldSurfaceSampler.UNEXPLORED;
        }
        int value = cell[(worldZ & 15) * CHUNK_SIDE + (worldX & 15)] & 0xFF;
        return value == EMPTY_CELL ? WorldSurfaceSampler.UNEXPLORED : value;
    }

    /** 退出游戏或换存档时清干净，避免拿着上一局的地图画。 */
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
