package com.xy2407.kingdom.server.path;

import com.xy2407.kingdom.common.path.SnapshotRepository;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 区块与方块事件接线，维护寻路快照的构建与失效
 */
public final class SnapshotEvents {
    private SnapshotEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.enqueue(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.drop(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        markDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        markDirty(event.getLevel(), event.getPos());
    }

    private static void markDirty(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository != null) {
            repository.markDirty(level, pos);
        }
    }
}