package com.xy2407.kingdom.server.map;

import java.util.Arrays;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapDataPacket;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端的地图分发：收到请求就采样，再按片发给提出请求的那名玩家。
 *
 * <p>每次请求都重新采样，不做缓存。理由是这样最简单也最不容易出错——
 * 地图只会在打开界面、拖动或缩放时请求，不是每帧；等真出现性能问题，
 * 再按 (维度, 区块) 加一层缓存也不迟。</p>
 */
public final class WorldMapServer {

    private WorldMapServer() {
    }

    /**
     * 采样以 (centerX, centerZ) 为中心的区域，并按 {@link WorldMapDataPacket#CHUNK_BYTES}
     * 切片下发。即使只探索了一小块，也是整块照发——未探索的格子值就是 0，
     * 客户端拿到后画成黑色，逻辑与表现都跟原版地图一致。
     */
    public static int fulfill(ServerPlayer player, int centerX, int centerZ, int radius) {
        ServerLevel level = player.serverLevel();
        WorldSurfaceSampler.Snapshot snapshot = WorldSurfaceSampler.sample(level, centerX, centerZ, radius);

        byte[] colors = snapshot.colors();
        int chunkTotal = (colors.length + WorldMapDataPacket.CHUNK_BYTES - 1)
                / WorldMapDataPacket.CHUNK_BYTES;
        if (chunkTotal <= 0) {
            chunkTotal = 1;
        }

        for (int index = 0; index < chunkTotal; index++) {
            int from = index * WorldMapDataPacket.CHUNK_BYTES;
            int to = Math.min(colors.length, from + WorldMapDataPacket.CHUNK_BYTES);
            /* 最后一片可能为空（colors 长度正好是整数倍时），给个空数组把总数交代清楚 */
            byte[] slice = from < to ? Arrays.copyOfRange(colors, from, to) : new byte[0];
            PacketDistributor.sendToPlayer(player, new WorldMapDataPacket(
                    snapshot.originX(), snapshot.originZ(), snapshot.size(), index, chunkTotal, slice));
        }

        return snapshot.exploredCount();
    }
}
