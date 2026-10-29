package com.xy2407.kingdom.server.path;

import com.xy2407.kingdom.ModMain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 区块加载驱动快照：已加载区块的全高度单元进入构建队列，常加载区块在世界生成时同样触发。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class SnapshotChunkEvents {

    private SnapshotChunkEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getChunk() instanceof LevelChunk chunk) {
            UnitSnapshotStore.INSTANCE.onChunkLoaded(level, chunk.getPos());
        }
    }
}
