package com.xy2407.kingdom.client.map;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapDataPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端的地图数据缓存：把服务端分片下发的像素拼成完整的一张。
 *
 * <p>拼装方式照 {@code DbViewerScreen} 那套：{@code chunkIndex == 0} 的那片视为新一张，
 * 先按 {@code size} 开好数组并清空计数，后续各片按 {@code chunkIndex × CHUNK_BYTES}
 * 的位置拷进去；收齐后 {@link #revision()} 自增，绘制方据此判断要不要重建像素表。</p>
 *
 * <p>{@link #colors()} 里存的是原版 {@code MapColor} 打包 id，**无符号**，
 * 取用时务必 {@code & 0xFF}；{@link WorldSurfaceSampler#UNEXPLORED} 表示未探索。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class WorldMapClientData {

    private static int originX;
    private static int originZ;
    private static int size;
    private static byte[] colors;
    private static int received;
    private static int total = 1;
    private static long revision;

    /** 已发出请求、还没收到第一片。避免每帧重复请求。 */
    private static boolean pending;

    private WorldMapClientData() {
    }

    public static void accept(WorldMapDataPacket packet) {
        if (packet.chunkIndex() <= 0) {
            /* 第一片：视为新一张图，重开缓冲区 */
            size = Math.max(0, packet.size());
            originX = packet.originX();
            originZ = packet.originZ();
            colors = size > 0 ? new byte[size * size] : null;
            received = 0;
        }
        if (colors == null) {
            return;
        }

        total = Math.max(1, packet.chunkTotal());
        byte[] slice = packet.colors();
        int from = packet.chunkIndex() * WorldMapDataPacket.CHUNK_BYTES;
        if (slice != null && from >= 0 && from < colors.length) {
            int length = Math.min(slice.length, colors.length - from);
            System.arraycopy(slice, 0, colors, from, length);
        }

        received++;
        pending = false;
        if (received >= total) {
            revision++;
        }
    }

    /** 数据是否已经收齐。没收齐时元素不该去读像素。 */
    public static boolean isComplete() {
        return colors != null && size > 0 && received >= total;
    }

    public static boolean isPending() {
        return pending;
    }

    public static void markPending() {
        pending = true;
    }

    /** 每次收齐自增，绘制方用它判断是否需要重建。 */
    public static long revision() {
        return revision;
    }

    public static int originX() {
        return originX;
    }

    public static int originZ() {
        return originZ;
    }

    public static int size() {
        return size;
    }

    public static byte[] colors() {
        return colors;
    }

    /** 取某个世界坐标的打包 id（已按无符号展开）；图外返回未探索。 */
    public static int packedAt(int worldX, int worldZ) {
        if (colors == null || size <= 0) {
            return WorldSurfaceSampler.UNEXPLORED & 0xFF;
        }
        int x = worldX - originX;
        int z = worldZ - originZ;
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return WorldSurfaceSampler.UNEXPLORED & 0xFF;
        }
        return colors[z * size + x] & 0xFF;
    }

    /** 退出游戏或关掉界面时清干净，避免拿着上一局的数据画。 */
    public static void clear() {
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
