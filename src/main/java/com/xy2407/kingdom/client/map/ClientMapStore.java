package com.xy2407.kingdom.client.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 客户端地图仓库：按维度累积区块地表像素并落盘，供地图元素直接取色。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMapStore {

    private static final int MAGIC = 0x4B4D4150;
    private static final int VERSION = 5;
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    private static final int CHUNK_SIZE = CHUNK_CELLS * 2;
    private static final long FLUSH_INTERVAL_NANOS = 30_000_000_000L;
    private static final String FOLDER = "kingdom-client-map";

    private static final Map<ResourceKey<Level>, Map<Long, int[]>> CHUNKS = new HashMap<>();

    private static final Map<ResourceKey<Level>, Map<Long, Map<Integer, int[]>>> LEVELS = new HashMap<>();

    public static final int MAX_LEVEL = 4;
    private static final Map<ResourceKey<Level>, int[]> CENTERS = new HashMap<>();
    private static final Set<ResourceKey<Level>> DIRTY = new HashSet<>();

    private static final int DIRTY_RING = 1024;
    private static final long[] DIRTY_KEYS = new long[DIRTY_RING];
    private static final long[] DIRTY_STAMPS = new long[DIRTY_RING];

    private static ResourceKey<Level> focused;
    private static String world = "";
    private static long revision;
    private static long stamp;
    private static long focusToken;
    private static int dirtyCursor;
    private static long lastFlushNanos;
    private static ExecutorService io;

    private ClientMapStore() {
    }

    public static void focus(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return;
        }
        String current = worldKey();
        if (!current.equals(world)) {
            ModMain.LOGGER.info("[地图] 世界切换 [{}] → [{}]：落盘旧世界、清空内存地图并作废当前视图",
                    world, current);
            flushNow();
            CHUNKS.clear();
            CENTERS.clear();
            LEVELS.clear();
            DIRTY.clear();
            world = current;
            focused = null;
            revision++;
            stamp++;
            focusToken++;
        }
        if (dimension.equals(focused)) {
            return;
        }
        ModMain.LOGGER.info("[地图] 切换维度 {} → {}，读取该维度的本地地图",
                focused, dimension.location());
        focused = dimension;
        focusToken++;
        load(dimension);
    }

    public static boolean has(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Map<Long, int[]> chunks = CHUNKS.get(dimension);
        return chunks != null && chunks.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static boolean explored(int chunkX, int chunkZ) {
        Map<Long, int[]> chunks = focused == null ? null : CHUNKS.get(focused);
        return chunks != null && chunks.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void record(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                              int[] colors, int[] heights) {
        if (dimension == null || colors == null || heights == null
                || colors.length < CHUNK_CELLS || heights.length < CHUNK_CELLS) {
            return;
        }
        boolean wrote = false;
        for (int index = 0; index < CHUNK_CELLS; index++) {
            if (colors[index] != WorldSurfaceSampler.UNEXPLORED) {
                wrote = true;
                break;
            }
        }
        if (!wrote) {
            return;
        }
        Map<Long, int[]> chunks = CHUNKS.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        long id = ChunkPos.asLong(chunkX, chunkZ);
        int[] cell = chunks.computeIfAbsent(id, key -> {
            int[] created = new int[CHUNK_SIZE];
            Arrays.fill(created, CHUNK_CELLS, CHUNK_SIZE, WorldSurfaceSampler.NO_SURFACE);
            return created;
        });
        boolean changed = false;
        synchronized (cell) {
            for (int index = 0; index < CHUNK_CELLS; index++) {
                int color = colors[index];
                if (color == WorldSurfaceSampler.UNEXPLORED) {
                    continue;
                }
                if (cell[index] != color) {
                    cell[index] = color;
                    changed = true;
                }
                int height = heights[index];
                if (height != WorldSurfaceSampler.NO_SURFACE
                        && cell[CHUNK_CELLS + index] != height) {
                    cell[CHUNK_CELLS + index] = height;
                    changed = true;
                }
            }
        }
        if (changed) {
            Map<Long, Map<Integer, int[]>> dimensionLevels = LEVELS.get(dimension);
            if (dimensionLevels != null) {
                dimensionLevels.remove(id);
            }
            DIRTY.add(dimension);
            stamp++;
            DIRTY_KEYS[dirtyCursor] = id;
            DIRTY_STAMPS[dirtyCursor] = stamp;
            dirtyCursor = (dirtyCursor + 1) % DIRTY_RING;
            revision++;
        }
    }

    public static int[] chunk(int chunkX, int chunkZ) {
        Map<Long, int[]> chunks = focused == null ? null : CHUNKS.get(focused);
        return chunks == null ? null : chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static int[] chunkAt(ResourceKey<Level> dimension, int chunkX, int chunkZ, int level) {
        if (level <= 0) {
            return chunk(chunkX, chunkZ);
        }
        Map<Long, int[]> chunks = CHUNKS.get(dimension);
        if (chunks == null || !chunks.containsKey(ChunkPos.asLong(chunkX, chunkZ))) {
            return null;
        }
        Map<Long, Map<Integer, int[]>> dimensionLevels =
                LEVELS.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        Map<Integer, int[]> levels =
                dimensionLevels.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                        key -> new ConcurrentHashMap<>());
        int[] cached = levels.get(level);
        if (cached != null) {
            return cached;
        }
        int[] built = buildLevel(dimension, chunkX, chunkZ, level);
        levels.put(level, built);
        return built;
    }

    private static int[] buildLevel(ResourceKey<Level> dimension, int chunkX, int chunkZ, int level) {
        int side = CHUNK_SIDE >> level;
        int area = side * side;
        int[] result = new int[area * 2];
        Arrays.fill(result, area, area * 2, WorldSurfaceSampler.NO_SURFACE);
        int[] source = chunkAt(dimension, chunkX, chunkZ, level - 1);
        if (source == null) {
            return result;
        }
        int sourceSide = CHUNK_SIDE >> (level - 1);
        int sourceArea = sourceSide * sourceSide;
        for (int ty = 0; ty < side; ty++) {
            for (int tx = 0; tx < side; tx++) {
                long sumR = 0;
                long sumG = 0;
                long sumB = 0;
                long sumH = 0;
                int colorCount = 0;
                int hCount = 0;
                for (int sy = 0; sy < 2; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int sourceIndex = (ty * 2 + sy) * sourceSide + (tx * 2 + sx);
                        int argb = source[sourceIndex];
                        if (argb != 0) {
                            sumR += (argb >> 16) & 0xFF;
                            sumG += (argb >> 8) & 0xFF;
                            sumB += argb & 0xFF;
                            colorCount++;
                        }
                        float h = source[sourceArea + sourceIndex];
                        if (h != WorldSurfaceSampler.NO_SURFACE) {
                            sumH += h;
                            hCount++;
                        }
                    }
                }
                int targetIndex = ty * side + tx;
                if (colorCount > 0) {
                    result[targetIndex] = 0xFF000000
                            | ((int) (sumR / colorCount)) << 16
                            | ((int) (sumG / colorCount)) << 8
                            | (int) (sumB / colorCount);
                }
                if (hCount > 0) {
                    result[area + targetIndex] = Math.round(sumH / hCount);
                }
            }
        }
        return result;
    }

    public static boolean hasAnyChunk() {
        Map<Long, int[]> chunks = focused == null ? null : CHUNKS.get(focused);
        return chunks != null && !chunks.isEmpty();
    }

    public static int chunkCount() {
        Map<Long, int[]> chunks = focused == null ? null : CHUNKS.get(focused);
        return chunks == null ? 0 : chunks.size();
    }

    public static int totalChunkCount() {
        int total = 0;
        for (Map<Long, int[]> chunks : CHUNKS.values()) {
            total += chunks.size();
        }
        return total;
    }

    public static long revision() {
        return revision;
    }

    public static long stamp() {
        return stamp;
    }

    public static long focusToken() {
        return focusToken;
    }

    public static boolean forEachDirtySince(long since, LongConsumer consumer) {
        if (since <= 0) {
            return false;
        }
        if (since < stamp - DIRTY_RING) {
            ModMain.LOGGER.info("[地图] 脏区块记录溢出：since={} 仅保留到 {}，改走全量重绘",
                    since, stamp - DIRTY_RING);
            return false;
        }
        for (int index = 0; index < DIRTY_RING; index++) {
            long entry = DIRTY_STAMPS[index];
            if (entry > since && entry <= stamp) {
                consumer.accept(DIRTY_KEYS[index]);
            }
        }
        return true;
    }

    public static int[] center(ResourceKey<Level> dimension) {
        int[] center = CENTERS.get(dimension);
        return center == null ? new int[] {0, 0} : new int[] {center[0], center[1]};
    }

    public static int[] focusedCenter() {
        return focused == null ? new int[] {0, 0} : center(focused);
    }

    public static void setFocusedCenter(int x, int z) {
        setCenter(focused, x, z);
    }

    public static void setCenter(ResourceKey<Level> dimension, int x, int z) {
        if (dimension == null) {
            return;
        }
        int[] center = CENTERS.computeIfAbsent(dimension, key -> new int[] {0, 0});
        if (center[0] == x && center[1] == z) {
            return;
        }
        center[0] = x;
        center[1] = z;
        DIRTY.add(dimension);
    }

    public static void flushIfDue() {
        if (DIRTY.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastFlushNanos < FLUSH_INTERVAL_NANOS) {
            return;
        }
        lastFlushNanos = now;
        flushAsync();
    }

    public static void flushNow() {
        if (DIRTY.isEmpty()) {
            return;
        }
        lastFlushNanos = System.nanoTime();
        Map<ResourceKey<Level>, Map<Long, int[]>> snapshot = snapshot();
        DIRTY.clear();
        String target = world;
        for (Map.Entry<ResourceKey<Level>, Map<Long, int[]>> entry : snapshot.entrySet()) {
            write(target, entry.getKey(), entry.getValue());
        }
    }

    public static void shutdown() {
        flushNow();
        CHUNKS.clear();
        CENTERS.clear();
        DIRTY.clear();
        focused = null;
        world = "";
        revision++;
        focusToken++;
        ModMain.LOGGER.info("[地图] 客户端地图仓库已清空（登出）");
    }

    private static void flushAsync() {
        Map<ResourceKey<Level>, Map<Long, int[]>> snapshot = snapshot();
        DIRTY.clear();
        String target = world;
        io().execute(() -> {
            for (Map.Entry<ResourceKey<Level>, Map<Long, int[]>> entry : snapshot.entrySet()) {
                write(target, entry.getKey(), entry.getValue());
            }
        });
    }

    private static Map<ResourceKey<Level>, Map<Long, int[]>> snapshot() {
        Map<ResourceKey<Level>, Map<Long, int[]>> result = new HashMap<>();
        for (ResourceKey<Level> dimension : new HashSet<>(DIRTY)) {
            Map<Long, int[]> chunks = CHUNKS.get(dimension);
            if (chunks == null) {
                continue;
            }
            Map<Long, int[]> copy = new HashMap<>(chunks.size());
            for (Map.Entry<Long, int[]> entry : chunks.entrySet()) {
                int[] cell;
                synchronized (entry.getValue()) {
                    cell = entry.getValue().clone();
                }
                copy.put(entry.getKey(), cell);
            }
            result.put(dimension, copy);
        }
        return result;
    }

    private static void load(ResourceKey<Level> dimension) {
        focusToken++;
        Path path = fileOf(world, dimension);
        if (!Files.isRegularFile(path)) {
            ModMain.LOGGER.info("[地图] {} 没有本地缓存文件，本次从空白开始：{}",
                    dimension.location(), path);
            return;
        }
        Map<Long, int[]> chunks = new ConcurrentHashMap<>();
        int[] center = new int[] {0, 0};
        int loaded = 0;
        boolean stale = false;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                stale = true;
            } else {
                center[0] = in.readInt();
                center[1] = in.readInt();
                int count = Math.max(0, in.readInt());
                for (int index = 0; index < count; index++) {
                    long id = in.readLong();
                    int[] cell = new int[CHUNK_SIZE];
                    Arrays.fill(cell, CHUNK_CELLS, CHUNK_SIZE, WorldSurfaceSampler.NO_SURFACE);
                    for (int pixel = 0; pixel < CHUNK_SIZE; pixel++) {
                        cell[pixel] = in.readInt();
                    }
                    chunks.put(id, cell);
                }
                loaded = count;
            }
        } catch (IOException failure) {
            ModMain.LOGGER.error("[地图] 客户端地图读盘失败（本次从空白开始，会重新采样）{}：{}",
                    path, failure.toString());
            return;
        }
        if (stale) {
            ModMain.LOGGER.warn("[地图] {} 的本地缓存版本不符（当前需要 v{}），已丢弃该文件、本次会重新采样：{}",
                    dimension.location(), VERSION, path);
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
            return;
        }
        CHUNKS.put(dimension, chunks);
        LEVELS.remove(dimension);
        CENTERS.put(dimension, center);
        revision++;
        focusToken++;
        ModMain.LOGGER.info("[地图] 载入本地地图 {}：{} 个区块（中心 {}，{}）",
                dimension.location(), loaded, center[0], center[1]);
    }

    private static void write(String target, ResourceKey<Level> dimension, Map<Long, int[]> chunks) {
        if (target.isBlank() || chunks == null) {
            return;
        }
        Path path = fileOf(target, dimension);
        int[] center = CENTERS.getOrDefault(dimension, new int[] {0, 0});
        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(center[0]);
                out.writeInt(center[1]);
                out.writeInt(chunks.size());
                for (Map.Entry<Long, int[]> entry : chunks.entrySet()) {
                    out.writeLong(entry.getKey());
                    for (int pixel = 0; pixel < CHUNK_SIZE; pixel++) {
                        out.writeInt(entry.getValue()[pixel]);
                    }
                }
            }
        } catch (IOException failure) {
            ModMain.LOGGER.error("[地图] 客户端地图写盘失败（缓存存不下来，下次重进世界会全部重新采样）{}：{}",
                    path, failure.toString());
        }
    }

    private static Path fileOf(String target, ResourceKey<Level> dimension) {
        String name = dimension.location().getNamespace() + "_" + dimension.location().getPath();
        return FMLPaths.GAMEDIR.get()
                .resolve(FOLDER)
                .resolve(safeFolder(target))
                .resolve(name + ".bin");
    }

    private static String safeFolder(String target) {
        String safe = sanitize(target);
        if (safe.equals(target)) {
            return safe;
        }
        return safe + "_" + Integer.toHexString(target.hashCode());
    }

    private static String worldKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return "";
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            String level = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            if (level != null && !level.isBlank()) {
                return "sp_" + level;
            }
            Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            return "sp_" + root.toAbsolutePath().normalize();
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "mp_" + server.ip;
        }
        return "";
    }

    private static String sanitize(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            boolean safe = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '-' || current == '.';
            builder.append(safe ? current : '_');
        }
        return builder.toString();
    }

    private static ExecutorService io() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "kingdom-map-io");
                thread.setDaemon(true);
                return thread;
            });
        }
        return io;
    }
}
