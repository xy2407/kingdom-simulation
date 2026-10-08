package com.xy2407.kingdom.client.map;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 客户端地图采样驱动：区块加载入队，客户端 tick 分帧采成像素写进本地仓库。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class ClientMapScanner {

    private static final int BUDGET_PER_TICK = 2;
    private static final int QUEUE_LIMIT = 8192;
    private static final int PLAYER_RADIUS_CHUNKS = 6;
    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int VISIBLE_LIMIT = 1024;
    private static final long VISIBLE_GAP_NANOS = 250_000_000L;

    private static final Deque<Long> URGENT = new ArrayDeque<>();
    private static final Deque<Long> NORMAL = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();

    private static ResourceKey<Level> dimension;
    private static int ticks;
    private static long lastVisibleNanos;

    private ClientMapScanner() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        enqueue(level.dimension(), event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearQueue();
        ClientMapStore.shutdown();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        ClientMapStore.focus(level.dimension());
        if (++ticks % RESCAN_INTERVAL_TICKS == 0) {
            enqueueAround(level, minecraft.player);
        }
        drain(level);
        ClientMapStore.flushIfDue();
    }

    public static void requestVisible(int minX, int minZ, int maxX, int maxZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (level == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastVisibleNanos < VISIBLE_GAP_NANOS) {
            return;
        }
        lastVisibleNanos = now;
        ResourceKey<Level> key = level.dimension();
        int offered = 0;
        for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4) && offered < VISIBLE_LIMIT; chunkZ++) {
            for (int chunkX = minX >> 4; chunkX <= (maxX >> 4) && offered < VISIBLE_LIMIT; chunkX++) {
                if (ClientMapStore.has(key, chunkX, chunkZ)
                        || !level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                long id = ChunkPos.asLong(chunkX, chunkZ);
                if (!QUEUED.add(id)) {
                    continue;
                }
                URGENT.addFirst(id);
                offered++;
            }
        }
    }

    private static void enqueueAround(ClientLevel level, LocalPlayer player) {
        if (player == null) {
            return;
        }
        ResourceKey<Level> key = level.dimension();
        ChunkPos center = player.chunkPosition();
        for (int dz = -PLAYER_RADIUS_CHUNKS; dz <= PLAYER_RADIUS_CHUNKS; dz++) {
            for (int dx = -PLAYER_RADIUS_CHUNKS; dx <= PLAYER_RADIUS_CHUNKS; dx++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                if (!ClientMapStore.has(key, chunkX, chunkZ)) {
                    enqueue(key, new ChunkPos(chunkX, chunkZ));
                }
            }
        }
    }

    private static void enqueue(ResourceKey<Level> key, ChunkPos pos) {
        if (!key.equals(dimension)) {
            clearQueue();
            dimension = key;
        }
        long id = ChunkPos.asLong(pos.x, pos.z);
        if (!QUEUED.add(id)) {
            return;
        }
        if (NORMAL.size() >= QUEUE_LIMIT) {
            Long dropped = NORMAL.pollLast();
            if (dropped != null) {
                QUEUED.remove(dropped);
            }
        }
        NORMAL.addLast(id);
    }

    private static void drain(ClientLevel level) {
        for (int done = 0; done < BUDGET_PER_TICK; done++) {
            Long id = URGENT.pollFirst();
            if (id == null) {
                id = NORMAL.pollFirst();
            }
            if (id == null) {
                return;
            }
            QUEUED.remove(id);
            int chunkX = ChunkPos.getX(id);
            int chunkZ = ChunkPos.getZ(id);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                continue;
            }
            int[] colors = WorldSurfaceSampler.sampleChunk(level, chunkX, chunkZ);
            ClientMapStore.record(level.dimension(), chunkX, chunkZ, colors);
        }
    }

    private static void clearQueue() {
        URGENT.clear();
        NORMAL.clear();
        QUEUED.clear();
        dimension = null;
    }
}
