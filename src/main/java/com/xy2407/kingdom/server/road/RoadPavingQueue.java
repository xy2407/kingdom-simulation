package com.xy2407.kingdom.server.road;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.road.RoadPolyline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 世界道路的铺路队列：全局 FIFO（W12），每 40 tick 强加载一批 5 区块、铺沙砾、落库、立刻卸载（W11）。
 */
public final class RoadPavingQueue {

    private static final int BATCH_INTERVAL_TICKS = 40;

    private static final int BATCH_CHUNKS = 5;

    private static final int BATCH_WAIT_LIMIT_STEPS = 5;

    private static final int LOW_TICK_FREE_LEVEL = 33;

    private static final TicketType<ChunkPos> ROAD_TICKET =
            TicketType.create("kingdom_road_paving", Comparator.comparingLong(ChunkPos::toLong));

    private record Task(UUID playerId, String dimension, List<Long> chunkOrder,
                        Map<Long, List<Long>> cellsByChunk) {
    }

    private static final Deque<Task> PENDING = new ArrayDeque<>();

    private static final Map<String, Set<Long>> TICKETED = new HashMap<>();

    private static Task current;

    private static int nextChunkIndex;

    private static List<Long> batch;

    private static int batchWaitSteps;

    private static int tickCounter;

    private RoadPavingQueue() {
    }

    public static void enqueue(UUID playerId, String dimension, Map<Long, List<Long>> cellsByChunk) {
        if (cellsByChunk == null || cellsByChunk.isEmpty()) {
            return;
        }
        List<Long> order = new ArrayList<>(cellsByChunk.keySet());
        PENDING.addLast(new Task(playerId, dimension, order, cellsByChunk));
        ModMain.LOGGER.info("世界道路入队：{} 个区块，当前队列长度 {}", order.size(), PENDING.size());
    }

    public static void reset() {
        PENDING.clear();
        TICKETED.clear();
        current = null;
        batch = null;
        nextChunkIndex = 0;
        batchWaitSteps = 0;
        tickCounter = 0;
    }

    public static void tick(MinecraftServer server) {
        if (server == null || (current == null && PENDING.isEmpty())) {
            return;
        }
        if (++tickCounter % BATCH_INTERVAL_TICKS != 0) {
            return;
        }
        step(server);
    }

    private static void step(MinecraftServer server) {
        if (current == null) {
            current = PENDING.poll();
            nextChunkIndex = 0;
            batch = null;
            if (current == null) {
                return;
            }
        }
        if (batch == null) {
            batch = takeBatch();
            if (batch.isEmpty()) {
                ModMain.LOGGER.info("世界道路任务铺完：玩家 {}", current.playerId());
                current = null;
                return;
            }
            batchWaitSteps = 0;
            for (long key : batch) {
                retain(server, current.dimension(), new ChunkPos(key));
            }
        }
        ServerLevel level = level(server, current.dimension());
        if (level == null) {
            ModMain.LOGGER.warn("世界道路任务维度不存在，放弃该批：{}", current.dimension());
            releaseBatch(server, current.dimension(), batch);
            batch = null;
            return;
        }
        if (!allLoaded(level, batch)) {
            batchWaitSteps++;
            if (batchWaitSteps > BATCH_WAIT_LIMIT_STEPS) {
                ModMain.LOGGER.warn("世界道路批次加载超时（200 tick），跳过 {} 个区块", batch.size());
                releaseBatch(server, current.dimension(), batch);
                batch = null;
            }
            return;
        }
        paveBatch(level, current, batch);
        releaseBatch(server, current.dimension(), batch);
        batch = null;
    }

    private static List<Long> takeBatch() {
        List<Long> result = new ArrayList<>();
        while (result.size() < BATCH_CHUNKS && nextChunkIndex < current.chunkOrder().size()) {
            result.add(current.chunkOrder().get(nextChunkIndex++));
        }
        return result;
    }

    private static boolean allLoaded(ServerLevel level, List<Long> chunks) {
        for (long key : chunks) {
            ChunkPos pos = new ChunkPos(key);
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) {
                return false;
            }
        }
        return true;
    }

    private static void paveBatch(ServerLevel level, Task task, List<Long> chunks) {
        long day = level.getDayTime() / 24000L;
        int placed = 0;
        for (long chunkKey : chunks) {
            ChunkPos pos = new ChunkPos(chunkKey);
            List<Long> cells = task.cellsByChunk().get(chunkKey);
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            List<Long> paved = new ArrayList<>(cells.size());
            int refY = WorldSurfaceSampler.NO_WALKABLE;
            for (long cell : cells) {
                BlockPos column = BlockPos.of(cell);
                int y = WorldSurfaceSampler.walkableSurfaceY(level, column.getX(), column.getZ(), refY);
                if (y == WorldSurfaceSampler.NO_WALKABLE) {
                    continue;
                }
                refY = y;
                BlockPos surface = new BlockPos(column.getX(), y, column.getZ());
                if (level.getBlockState(surface).is(Blocks.GRAVEL)) {
                    paved.add(surface.asLong());
                    continue;
                }
                if (level.getBlockEntity(surface) != null) {
                    level.removeBlockEntity(surface);
                }
                level.setBlock(surface, Blocks.GRAVEL.defaultBlockState(), RoadService.PLACE_FLAGS);
                paved.add(surface.asLong());
                placed++;
            }
            RoadStore.INSTANCE.recordChunk(task.dimension(), pos.x, pos.z, paved.stream()
                    .mapToLong(Long::longValue).toArray(), task.playerId().toString(), day);
        }
        ModMain.LOGGER.info("世界道路批次铺完：{} 个区块、新增 {} 格（玩家 {}）",
                chunks.size(), placed, task.playerId());
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            return server.getLevel(key);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static void retain(MinecraftServer server, String dimension, ChunkPos chunk) {
        ServerLevel level = level(server, dimension);
        if (level == null) {
            return;
        }
        Set<Long> chunks = TICKETED.computeIfAbsent(dimension, key -> new HashSet<>());
        if (chunks.add(chunk.toLong())) {
            level.getChunkSource().addRegionTicket(ROAD_TICKET, chunk, LOW_TICK_FREE_LEVEL, chunk);
        }
    }

    private static void releaseBatch(MinecraftServer server, String dimension, List<Long> chunks) {
        ServerLevel level = level(server, dimension);
        if (level == null) {
            return;
        }
        Set<Long> ticketed = TICKETED.get(dimension);
        for (long key : chunks) {
            ChunkPos chunk = new ChunkPos(key);
            if (ticketed == null || ticketed.remove(key)) {
                level.getChunkSource().removeRegionTicket(ROAD_TICKET, chunk, LOW_TICK_FREE_LEVEL, chunk);
            }
        }
    }

    public static void releaseAll(MinecraftServer server) {
        for (Map.Entry<String, Set<Long>> entry : TICKETED.entrySet()) {
            ServerLevel level = level(server, entry.getKey());
            if (level == null) {
                continue;
            }
            for (long key : entry.getValue()) {
                ChunkPos chunk = new ChunkPos(key);
                level.getChunkSource().removeRegionTicket(ROAD_TICKET, chunk, LOW_TICK_FREE_LEVEL, chunk);
            }
        }
        PENDING.clear();
        TICKETED.clear();
        current = null;
        batch = null;
    }
}
