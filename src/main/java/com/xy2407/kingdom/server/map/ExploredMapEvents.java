package com.xy2407.kingdom.server.map;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 已探索地图的事件接线：区块加载时把该块排进采样队列。
 *
 * <p>"走过就记得"靠的就是这一句。只入队（O(1)），真正的 256 列扫描由
 * {@link ExploredMapStore#tick} 分帧做，不会让区块加载变慢。</p>
 */
public final class ExploredMapEvents {

    private ExploredMapEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ExploredMapStore.enqueue(level, event.getChunk().getPos());
    }
}
