package com.xy2407.kingdom.server.path;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.SnapshotUnitSchema;
import com.xy2407.kingdom.common.path.SnapshotCodec;
import com.xy2407.kingdom.common.path.UnitKey;
import com.xy2407.kingdom.common.path.UnitSnapshot;
import com.xy2407.kingdom.common.path.UnitSnapshotFactory;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 单元快照仓库：三层 LRU + 磁盘缓存 + 分帧构建队列。
 */
public final class UnitSnapshotStore {

    public static final UnitSnapshotStore INSTANCE = new UnitSnapshotStore();

    private static final int[] TIER_CAPACITY = {24000, 7000, 15000};
    private static final int TIER_A = 0;
    private static final int TIER_B = 1;
    private static final int TIER_C = 2;
    private static final int RING_CHUNKS = 2;
    private static final int BUILD_PER_TICK = 100;
    private static final int REBUILD_PER_TICK = 24;
    private static final int RESTORE_PER_TICK = 256;
    private static final int RESTORE_BATCH = 64;
    private static final long DIRTY_COOLDOWN_TICKS = 4L;
    private static final int PERSISTED_RETRY_TICKS = 200;
    private static final int MIN_WINDOW_CHUNKS = 2;
    private static final int WINDOW_SCAN_INTERVAL = 240;
    private static final int DEFAULT_WINDOW_LAYERS = 4;
    private static final int TIER_MEMO_TICKS = 100;
    private static final int ENCODE_BACKLOG_LIMIT = 8192;

    private final Map<String, UnitSnapshot> cache = new LinkedHashMap<>(256, 0.75F, true);
    private final Map<String, Integer> tiers = new HashMap<>();
    private final int[] tierCounts = new int[3];
    private final List<LinkedHashSet<String>> tierOrder =
            List.of(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
    private final Map<String, Map<Long, Integer>> tierMemo = new HashMap<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private final Deque<String> urgentOrder = new ArrayDeque<>();
    private final Deque<String> backgroundOrder = new ArrayDeque<>();
    private final Deque<String> diskOrder = new ArrayDeque<>();
    private final Set<String> urgentKeys = new HashSet<>();
    private int builtLastTick;
    private int urgentBuiltLastTick;
    private int backgroundBuiltLastTick;
    private long builtTotal;
    private long requestedTotal;
    private long evictedTotal;
    private long restoredTotal;
    private long restoreMissTotal;
    private long skippedTotal;
    private long rebuiltTotal;
    private long dirtyMarkTotal;
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Map<String, Long> dirtyAt = new HashMap<>();
    private final Set<String> persisted = new HashSet<>();
    private final Set<String> sealed = new HashSet<>();
    private final Map<String, int[]> yWindows = new HashMap<>();
    private int windowCounter;
    private int tierMemoAge;
    private int persistedRetryTicks;
    private boolean persistedLoaded;
    private int windowUnits;
    private volatile byte[] emptyBlob;
    private final AtomicInteger encodePending = new AtomicInteger();
    private volatile ExecutorService encoder;

    private UnitSnapshotStore() {
    }

    public void reset() {
        synchronized (this) {
            cache.clear();
            pending.clear();
            urgentOrder.clear();
            backgroundOrder.clear();
            diskOrder.clear();
            urgentKeys.clear();
            builtLastTick = 0;
            urgentBuiltLastTick = 0;
            backgroundBuiltLastTick = 0;
            builtTotal = 0L;
            requestedTotal = 0L;
            evictedTotal = 0L;
            restoredTotal = 0L;
            restoreMissTotal = 0L;
            skippedTotal = 0L;
            rebuiltTotal = 0L;
            dirtyMarkTotal = 0L;
            dirty.clear();
            dirtyAt.clear();
            persisted.clear();
            tiers.clear();
            sealed.clear();
            for (int i = 0; i < tierCounts.length; i++) {
                tierCounts[i] = 0;
            }
            yWindows.clear();
            for (LinkedHashSet<String> order : tierOrder) {
                order.clear();
            }
            tierMemo.clear();
            tierMemoAge = 0;
            persistedLoaded = false;
            persistedRetryTicks = 0;
            windowUnits = 0;
        }
    }

    public UnitSnapshot get(String dimension, int cx, int layer, int cz) {
        String key = UnitKey.of(dimension, cx, layer, cz);
        synchronized (this) {
            UnitSnapshot unit = cache.get(key);
            if (unit != null) {
                return unit;
            }
        }
        request(dimension, cx, layer, cz);
        return null;
    }

    public void extendYWindow(String dimension, int baseY, int height) {
        int low = (baseY - DEFAULT_WINDOW_LAYERS * 16) >> 4;
        int high = (baseY + Math.max(height, 16) + DEFAULT_WINDOW_LAYERS * 16) >> 4;
        synchronized (this) {
            int[] window = yWindows.get(dimension);
            if (window == null) {
                yWindows.put(dimension, new int[] {low, high});
            } else {
                window[0] = Math.min(window[0], low);
                window[1] = Math.max(window[1], high);
            }
        }
    }

    public void request(String dimension, int cx, int layer, int cz) {
        request(dimension, cx, layer, cz, true);
    }

    public void requestBackground(String dimension, int cx, int layer, int cz) {
        request(dimension, cx, layer, cz, false);
    }

    private void request(String dimension, int cx, int layer, int cz, boolean urgent) {
        String key = UnitKey.of(dimension, cx, layer, cz);
        synchronized (this) {
            if (cache.containsKey(key)) {
                return;
            }
            if (!pending.contains(key)) {
                pending.add(key);
                requestedTotal++;
                if (persisted.contains(key)) {
                    if (urgent) {
                        diskOrder.addFirst(key);
                    } else {
                        diskOrder.add(key);
                    }
                    return;
                }
                if (urgent) {
                    urgentOrder.add(key);
                    urgentKeys.add(key);
                } else {
                    backgroundOrder.add(key);
                }
                return;
            }
            if (urgent && urgentKeys.add(key)) {
                urgentOrder.add(key);
            }
        }
    }

    public UnitSnapshot peek(String dimension, int cx, int layer, int cz) {
        synchronized (this) {
            return cache.get(UnitKey.of(dimension, cx, layer, cz));
        }
    }

    public int[] windowOf(String dimension) {
        synchronized (this) {
            int[] window = yWindows.get(dimension);
            return window == null ? null : new int[] {window[0], window[1]};
        }
    }

    public void onChunkLoaded(ServerLevel level, ChunkPos pos) {
        if (!withinKeepWindow(level, pos.x, pos.z)) {
            synchronized (this) {
                skippedTotal++;
            }
            return;
        }
        String dimension = level.dimension().location().toString();
        int minLayer = level.getMinBuildHeight() >> 4;
        int maxLayer = (level.getMaxBuildHeight() - 1) >> 4;
        for (int layer = minLayer; layer <= maxLayer; layer++) {
            request(dimension, pos.x, layer, pos.z, false);
        }
    }

    private boolean withinKeepWindow(ServerLevel level, int cx, int cz) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return true;
        }
        int radius = Math.max(MIN_WINDOW_CHUNKS, level.getServer().getPlayerList().getSimulationDistance());
        for (ServerPlayer player : players) {
            int dx = (player.getBlockX() >> 4) - cx;
            int dz = (player.getBlockZ() >> 4) - cz;
            if (dx >= -radius && dx <= radius && dz >= -radius && dz <= radius) {
                return true;
            }
        }
        return false;
    }

    public void markDirty(String dimension, int cx, int layer, int cz, long gameTime) {
        String key = UnitKey.of(dimension, cx, layer, cz);
        synchronized (this) {
            if (!cache.containsKey(key) && !persisted.contains(key)) {
                return;
            }
            Long last = dirtyAt.get(key);
            if (last != null && gameTime - last < DIRTY_COOLDOWN_TICKS) {
                return;
            }
            dirtyAt.put(key, gameTime);
            dirty.add(key);
            dirtyMarkTotal++;
        }
    }

    public void tick(MinecraftServer server) {
        ensurePersistedLoaded();
        boolean scanWindow = false;
        List<String> buildNow = new ArrayList<>(BUILD_PER_TICK);
        List<String> restoreNow = new ArrayList<>(RESTORE_PER_TICK);
        List<String> rebuildNow;
        synchronized (this) {
            if (++windowCounter >= WINDOW_SCAN_INTERVAL) {
                windowCounter = 0;
                scanWindow = true;
            }
            if (++tierMemoAge >= TIER_MEMO_TICKS) {
                tierMemoAge = 0;
                tierMemo.clear();
            }
            while (restoreNow.size() < RESTORE_PER_TICK) {
                String diskKey = diskOrder.poll();
                if (diskKey == null) {
                    break;
                }
                if (!pending.remove(diskKey)) {
                    continue;
                }
                restoreNow.add(diskKey);
            }
            int urgentLimit = Math.max(1, BUILD_PER_TICK * 4 / 5);
            int urgentTaken = 0;
            int urgentBuilt = 0;
            while (buildNow.size() < BUILD_PER_TICK) {
                String key = null;
                boolean fromUrgent = false;
                if (urgentTaken < urgentLimit) {
                    key = urgentOrder.poll();
                    if (key != null) {
                        urgentTaken++;
                        urgentKeys.remove(key);
                        fromUrgent = true;
                    }
                }
                if (key == null) {
                    key = backgroundOrder.poll();
                }
                if (key == null) {
                    break;
                }
                if (!pending.remove(key)) {
                    continue;
                }
                buildNow.add(key);
                if (fromUrgent) {
                    urgentBuilt++;
                }
            }
            urgentBuiltLastTick = urgentBuilt;
            backgroundBuiltLastTick = buildNow.size() - urgentBuilt;
            rebuildNow = dirty.isEmpty() ? List.of() : new ArrayList<>(dirty);
            dirty.clear();
            if (rebuildNow.size() > REBUILD_PER_TICK) {
                for (int i = REBUILD_PER_TICK; i < rebuildNow.size(); i++) {
                    dirty.add(rebuildNow.get(i));
                }
                rebuildNow = new ArrayList<>(rebuildNow.subList(0, REBUILD_PER_TICK));
            }
        }
        if (scanWindow) {
            int sim = Math.max(2, server.getPlayerList().getSimulationDistance());
            int players = Math.max(1, server.getPlayerList().getPlayers().size());
            windowUnits = players * (2 * sim + 1) * (2 * sim + 1) * 65;
            collectWindow(server);
        }
        if (!restoreNow.isEmpty()) {
            restoreFromDisk(restoreNow);
        }
        for (String key : rebuildNow) {
            rebuild(key, server);
        }
        for (String key : buildNow) {
            build(key, server, true);
        }
        builtLastTick = buildNow.size();
        builtTotal += builtLastTick;
        rebuiltTotal += rebuildNow.size();
    }

    public int buildNow(MinecraftServer server, String dimension, BlockPos min, BlockPos max) {
        int built = 0;
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                for (int layer = min.getY() >> 4; layer <= max.getY() >> 4; layer++) {
                    String key = UnitKey.of(dimension, cx, layer, cz);
                    synchronized (this) {
                        removeCache(key);
                        pending.remove(key);
                        urgentKeys.remove(key);
                    }
                    build(key, server, true);
                    built++;
                }
            }
        }
        return built;
    }

    private void build(String key, MinecraftServer server, boolean persist) {
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return;
        }
        ServerLevel level = levelOf(server, parts[0]);
        if (level == null) {
            return;
        }
        int cx;
        int layer;
        int cz;
        try {
            cx = Integer.parseInt(parts[1]);
            layer = Integer.parseInt(parts[2]);
            cz = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ignored) {
            return;
        }
        UnitSnapshot unit = UnitSnapshotFactory.build(level, cx, layer, cz);
        if (unit == null) {
            return;
        }
        boolean sealedUnit = UnitSnapshotFactory.isSealed(level, cx, layer, cz);
        synchronized (this) {
            putCache(parts[0], cx, cz, key, unit);
            if (sealedUnit) {
                sealed.add(key);
            } else {
                sealed.remove(key);
            }
        }
        if (persist) {
            writeToDisk(key, unit);
        }
    }

    private void rebuild(String key, MinecraftServer server) {
        synchronized (this) {
            removeCache(key);
            persisted.remove(key);
        }
        build(key, server, true);
    }

    private void putCache(String dimension, int cx, int cz, String key, UnitSnapshot unit) {
        int tier = tierOf(dimension, cx, cz);
        Integer previous = tiers.put(key, tier);
        if (previous != null) {
            tierCounts[previous]--;
            if (previous != tier) {
                tierOrder.get(previous).remove(key);
            }
        }
        cache.put(key, unit);
        LinkedHashSet<String> order = tierOrder.get(tier);
        order.remove(key);
        order.add(key);
        tierCounts[tier]++;
        evict(tier);
    }

    private void removeCache(String key) {
        if (cache.remove(key) != null) {
            Integer tier = tiers.remove(key);
            if (tier != null) {
                tierCounts[tier]--;
                tierOrder.get(tier).remove(key);
            }
        }
        dirtyAt.remove(key);
    }

    private void evict(int tier) {
        LinkedHashSet<String> order = tierOrder.get(tier);
        while (tierCounts[tier] > Math.max(TIER_CAPACITY[tier], windowUnits)) {
            if (order.isEmpty()) {
                return;
            }
            String eldest = order.iterator().next();
            int before = tierCounts[tier];
            removeCache(eldest);
            if (tierCounts[tier] == before) {
                order.remove(eldest);
                return;
            }
            evictedTotal++;
        }
    }

    private int tierOf(String dimension, int cx, int cz) {
        if (dimension == null) {
            return TIER_C;
        }
        Map<Long, Integer> memo = tierMemo.computeIfAbsent(dimension, holder -> new HashMap<>());
        Long packed = ChunkPos.asLong(cx, cz);
        Integer cached = memo.get(packed);
        if (cached != null) {
            return cached;
        }
        int tier = computeTier(dimension, cx, cz);
        memo.put(packed, tier);
        return tier;
    }

    private int computeTier(String dimension, int cx, int cz) {
        if (TerritoryRegistry.INSTANCE.byChunk(dimension, cx, cz) != null) {
            return TIER_A;
        }
        for (int dx = -RING_CHUNKS; dx <= RING_CHUNKS; dx++) {
            for (int dz = -RING_CHUNKS; dz <= RING_CHUNKS; dz++) {
                if (TerritoryRegistry.INSTANCE.byChunk(dimension, cx + dx, cz + dz) != null) {
                    return TIER_B;
                }
            }
        }
        return TIER_C;
    }

    private void ensurePersistedLoaded() {
        if (persistedLoaded) {
            return;
        }
        if (persistedRetryTicks > 0) {
            persistedRetryTicks--;
            return;
        }
        if (!KingdomPersistence.isReady()) {
            return;
        }
        Set<String> loaded = new HashSet<>();
        try {
            int count = KingdomPersistence.querySync(connection -> {
                int rows = 0;
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + SnapshotUnitSchema.COL_KEY
                             + " FROM " + SnapshotUnitSchema.TABLE_SNAPSHOT)) {
                    while (rs.next()) {
                        loaded.add(rs.getString(1));
                        rows++;
                    }
                }
                return rows;
            });
            synchronized (this) {
                persisted.addAll(loaded);
                promotePersistedQueue();
            }
            persistedLoaded = true;
            ModMain.LOGGER.info("已索引 {} 个可复用快照单元", count);
        } catch (Exception failure) {
            persistedRetryTicks = PERSISTED_RETRY_TICKS;
            ModMain.LOGGER.error("加载快照索引失败，{} tick 后重试", PERSISTED_RETRY_TICKS, failure);
        }
    }

    private void promotePersistedQueue() {
        if (backgroundOrder.isEmpty() && urgentOrder.isEmpty()) {
            return;
        }
        int moved = 0;
        Deque<String> kept = new ArrayDeque<>(backgroundOrder.size());
        while (!backgroundOrder.isEmpty()) {
            String key = backgroundOrder.poll();
            if (persisted.contains(key)) {
                diskOrder.add(key);
                moved++;
            } else {
                kept.add(key);
            }
        }
        backgroundOrder.addAll(kept);
        kept.clear();
        while (!urgentOrder.isEmpty()) {
            String key = urgentOrder.poll();
            if (persisted.contains(key)) {
                urgentKeys.remove(key);
                diskOrder.addFirst(key);
                moved++;
            } else {
                kept.add(key);
            }
        }
        urgentOrder.addAll(kept);
        if (moved > 0) {
            ModMain.LOGGER.info("快照索引命中 {} 个待建单元，转由磁盘装载", moved);
        }
    }

    private void restoreFromDisk(List<String> keys) {
        for (int start = 0; start < keys.size(); start += RESTORE_BATCH) {
            restoreSlice(keys.subList(start, Math.min(keys.size(), start + RESTORE_BATCH)));
        }
    }

    private void restoreSlice(List<String> keys) {
        Map<String, byte[]> data = new HashMap<>(keys.size() * 2);
        Map<String, Integer> formats = new HashMap<>(keys.size() * 2);
        try {
            KingdomPersistence.querySync(connection -> {
                StringBuilder sql = new StringBuilder(128 + keys.size() * 40);
                sql.append("SELECT ").append(SnapshotUnitSchema.COL_KEY).append(',')
                        .append(SnapshotUnitSchema.COL_DATA).append(',')
                        .append(SnapshotUnitSchema.COL_FORMAT)
                        .append(" FROM ").append(SnapshotUnitSchema.TABLE_SNAPSHOT)
                        .append(" WHERE ").append(SnapshotUnitSchema.COL_KEY).append(" IN (");
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                }
                sql.append(')');
                try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < keys.size(); i++) {
                        ps.setString(i + 1, keys.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            byte[] raw = rs.getBytes(2);
                            if (raw != null) {
                                String key = rs.getString(1);
                                data.put(key, raw);
                                formats.put(key, rs.getInt(3));
                            }
                        }
                    }
                }
                return null;
            });
        } catch (Exception failure) {
            ModMain.LOGGER.error("批量读取快照失败 {} 个单元", keys.size(), failure);
            demote(keys);
            return;
        }
        int restored = 0;
        for (String key : keys) {
            byte[] raw = data.get(key);
            Integer format = formats.get(key);
            if (raw == null || format == null || format != SnapshotCodec.format()) {
                reject(key);
                continue;
            }
            UnitSnapshot unit = UnitSnapshot.deserialize(raw, format);
            String[] parts = key.split("\\|");
            if (unit == null || parts.length != 4) {
                reject(key);
                continue;
            }
            try {
                int cx = Integer.parseInt(parts[1]);
                int cz = Integer.parseInt(parts[3]);
                synchronized (this) {
                    putCache(parts[0], cx, cz, key, unit);
                }
                restored++;
            } catch (NumberFormatException ignored) {
                reject(key);
            }
        }
        synchronized (this) {
            restoredTotal += restored;
        }
    }

    private void reject(String key) {
        synchronized (this) {
            persisted.remove(key);
            restoreMissTotal++;
            if (pending.add(key)) {
                backgroundOrder.add(key);
            }
        }
    }

    private void demote(List<String> keys) {
        synchronized (this) {
            for (String key : keys) {
                restoreMissTotal++;
                if (pending.add(key)) {
                    backgroundOrder.add(key);
                }
            }
        }
    }

    private void writeToDisk(String key, UnitSnapshot unit) {
        if (!KingdomPersistence.isReady()) {
            return;
        }
        if (encodePending.get() >= ENCODE_BACKLOG_LIMIT) {
            commitSnapshot(key, unit);
            return;
        }
        encodePending.incrementAndGet();
        try {
            encoder().execute(() -> {
                try {
                    commitSnapshot(key, unit);
                } finally {
                    encodePending.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            encodePending.decrementAndGet();
            commitSnapshot(key, unit);
        }
    }

    private void commitSnapshot(String key, UnitSnapshot unit) {
        byte[] data = unit.isAir() ? emptyBlob() : unit.serialize();
        if (data == null) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        values.put(SnapshotUnitSchema.COL_DATA, data);
        values.put(SnapshotUnitSchema.COL_FORMAT, SnapshotCodec.format());
        values.put(SnapshotUnitSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(SnapshotUnitSchema.TABLE_SNAPSHOT, SnapshotUnitSchema.COL_KEY,
                key, values);
        synchronized (this) {
            persisted.add(key);
        }
    }

    private ExecutorService encoder() {
        ExecutorService current = encoder;
        if (current == null || current.isShutdown()) {
            current = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kingdom-snapshot-encoder");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
            encoder = current;
        }
        return current;
    }

    public void shutdown() {
        ExecutorService current = encoder;
        encoder = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            current.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] emptyBlob() {
        byte[] cached = emptyBlob;
        if (cached == null) {
            cached = UnitSnapshot.EMPTY.serialize();
            emptyBlob = cached;
        }
        return cached;
    }

    private ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    public List<String> report() {
        List<String> lines = new ArrayList<>();
        Map<String, int[]> ranges = new HashMap<>();
        long bytes = 0L;
        int units;
        int pendingSize;
        int urgentSize;
        int backgroundSize;
        int dirtySize;
        int sealedSize;
        int[] tiers;
        int lastTick;
        int lastUrgent;
        int lastBackground;
        long total;
        long requested;
        long evicted;
        long restored;
        long restoreMiss;
        long skipped;
        long rebuilt;
        long dirtyMark;
        int diskSize;
        int windowBase;
        synchronized (this) {
            units = cache.size();
            pendingSize = pending.size();
            urgentSize = urgentOrder.size();
            backgroundSize = backgroundOrder.size();
            dirtySize = dirty.size();
            sealedSize = sealed.size();
            tiers = new int[] {tierCounts[0], tierCounts[1], tierCounts[2]};
            lastTick = builtLastTick;
            lastUrgent = urgentBuiltLastTick;
            lastBackground = backgroundBuiltLastTick;
            total = builtTotal;
            requested = requestedTotal;
            evicted = evictedTotal;
            restored = restoredTotal;
            restoreMiss = restoreMissTotal;
            skipped = skippedTotal;
            rebuilt = rebuiltTotal;
            dirtyMark = dirtyMarkTotal;
            diskSize = diskOrder.size();
            windowBase = windowUnits;
            for (Map.Entry<String, UnitSnapshot> entry : cache.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                if (parts.length != 4) {
                    continue;
                }
                int cx;
                int layer;
                int cz;
                try {
                    cx = Integer.parseInt(parts[1]);
                    layer = Integer.parseInt(parts[2]);
                    cz = Integer.parseInt(parts[3]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                UnitSnapshot unit = entry.getValue();
                bytes += 2100L + unit.standCount() * 4L + unit.blockCount() * 2L;
                int[] range = ranges.computeIfAbsent(parts[0], key -> new int[] {cx, cx, layer, layer, cz, cz});
                range[0] = Math.min(range[0], cx);
                range[1] = Math.max(range[1], cx);
                range[2] = Math.min(range[2], layer);
                range[3] = Math.max(range[3], layer);
                range[4] = Math.min(range[4], cz);
                range[5] = Math.max(range[5], cz);
            }
        }
        lines.add("构建预算=" + BUILD_PER_TICK + "/tick  上次构建=" + lastTick
                + "（紧急 " + lastUrgent + " / 后台 " + lastBackground + "）"
                + "  累计构建=" + total + " / 重建 " + rebuilt + " / 标脏 " + dirtyMark
                + "  累计请求=" + requested + "  累计逐出=" + evicted);
        int buildQueued = Math.max(0, pendingSize - diskSize);
        lines.add("缓存单元=" + units + "（A " + tiers[0] + " / B " + tiers[1] + " / C " + tiers[2]
                + "）  待构建=" + buildQueued + "（队列 紧急 " + urgentSize + " / 后台 " + backgroundSize
                + "）  待装载=" + diskSize + "  未处理合计=" + pendingSize
                + "  标脏=" + dirtySize + "  密封=" + sealedSize);
        lines.add("磁盘复用=已装载 " + restored + " / 回退重建 " + restoreMiss
                + " / 远处丢弃 " + skipped
                + "  装载预算=" + RESTORE_PER_TICK + "/tick（" + RESTORE_BATCH + " 个一批）"
                + "  待编码=" + encodePending.get() + "  容量基准=" + windowBase);
        lines.add("");
        lines.add(String.format("估算内存≈%.1f MB", bytes / 1048576.0D));
        for (Map.Entry<String, int[]> entry : ranges.entrySet()) {
            int[] r = entry.getValue();
            int unitCount = (r[1] - r[0] + 1) * (r[3] - r[2] + 1) * (r[5] - r[4] + 1);
            lines.add("  " + entry.getKey()
                    + "  区块 x[" + r[0] + "," + r[1] + "] z[" + r[4] + "," + r[5] + "]"
                    + "  层[" + r[2] + "," + r[3] + "]");
            lines.add("    世界方块 X " + (r[0] << 4) + ".." + ((r[1] << 4) + 15)
                    + "  Y " + (r[2] << 4) + ".." + ((r[3] << 4) + 15)
                    + "  Z " + (r[4] << 4) + ".." + ((r[5] << 4) + 15)
                    + "  覆盖单元=" + unitCount);
        }
        return lines;
    }

    private void collectWindow(MinecraftServer server) {
        int windowChunks = Math.max(MIN_WINDOW_CHUNKS, server.getPlayerList().getSimulationDistance());
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            int minLayer = level.getMinBuildHeight() >> 4;
            int maxLayer = (level.getMaxBuildHeight() - 1) >> 4;
            for (ServerPlayer player : level.players()) {
                int playerChunkX = player.getBlockX() >> 4;
                int playerChunkZ = player.getBlockZ() >> 4;
                for (int dx = -windowChunks; dx <= windowChunks; dx++) {
                    for (int dz = -windowChunks; dz <= windowChunks; dz++) {
                        int cx = playerChunkX + dx;
                        int cz = playerChunkZ + dz;
                        if (!level.hasChunk(cx, cz)) {
                            continue;
                        }
                        for (int layer = minLayer; layer <= maxLayer; layer++) {
                            if (!sealed.isEmpty()
                                    && sealed.contains(UnitKey.of(dimension, cx, layer, cz))) {
                                continue;
                            }
                            request(dimension, cx, layer, cz, false);
                        }
                    }
                }
            }
        }
    }
}
