package com.xy2407.kingdom.server.path;

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
    private static final long DIRTY_COOLDOWN_TICKS = 4L;
    private static final int PLAYER_WINDOW_CHUNKS = 4;
    private static final int WINDOW_SCAN_INTERVAL = 100;
    private static final int DEFAULT_WINDOW_LAYERS = 4;

    private final Map<String, UnitSnapshot> cache = new LinkedHashMap<>(256, 0.75F, true);
    private final Map<String, Integer> tiers = new HashMap<>();
    private final int[] tierCounts = new int[3];
    private final Set<String> pending = new LinkedHashSet<>();
    private final Deque<String> urgentOrder = new ArrayDeque<>();
    private final Deque<String> backgroundOrder = new ArrayDeque<>();
    private final Set<String> urgentKeys = new HashSet<>();
    private int builtLastTick;
    private int urgentBuiltLastTick;
    private int backgroundBuiltLastTick;
    private long builtTotal;
    private long requestedTotal;
    private long evictedTotal;
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Map<String, Long> dirtyAt = new HashMap<>();
    private final Set<String> persisted = new HashSet<>();
    private final Set<String> sealed = new HashSet<>();
    private final Map<String, int[]> yWindows = new HashMap<>();
    private int windowCounter;

    private UnitSnapshotStore() {
    }

    public void reset() {
        synchronized (this) {
            cache.clear();
            pending.clear();
            urgentOrder.clear();
            backgroundOrder.clear();
            urgentKeys.clear();
            builtLastTick = 0;
            urgentBuiltLastTick = 0;
            backgroundBuiltLastTick = 0;
            builtTotal = 0L;
            requestedTotal = 0L;
            evictedTotal = 0L;
            dirty.clear();
            dirtyAt.clear();
            persisted.clear();
            tiers.clear();
            sealed.clear();
            for (int i = 0; i < tierCounts.length; i++) {
                tierCounts[i] = 0;
            }
            yWindows.clear();
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
        }
    }

    public void tick(MinecraftServer server) {
        List<String> windowKeys = null;
        List<String> buildNow = new ArrayList<>(BUILD_PER_TICK);
        List<String> rebuildNow;
        synchronized (this) {
            if (++windowCounter >= WINDOW_SCAN_INTERVAL) {
                windowCounter = 0;
                windowKeys = collectWindow(server);
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
        if (windowKeys != null) {
            for (String key : windowKeys) {
                requestKey(key);
            }
        }
        for (String key : rebuildNow) {
            rebuild(key, server);
        }
        for (String key : buildNow) {
            build(key, server, true);
        }
        builtLastTick = buildNow.size();
        builtTotal += builtLastTick;
    }

    private void requestKey(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return;
        }
        try {
            requestBackground(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
        }
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
                    build(key, server, false);
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
        int tier = tierOf(parts[0], cx, cz);
        synchronized (this) {
            putCache(parts[0], cx, cz, key, unit);
            if (sealedUnit) {
                sealed.add(key);
            }
        }
        if (persist && !sealedUnit && tier != TIER_C && unit.hasStand()) {
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
        Integer previous = tiers.put(key, tierOf(dimension, cx, cz));
        if (previous != null) {
            tierCounts[previous]--;
        }
        cache.put(key, unit);
        Integer tier = tiers.get(key);
        tierCounts[tier]++;
        evict(tier);
    }

    private void removeCache(String key) {
        if (cache.remove(key) != null) {
            Integer tier = tiers.remove(key);
            if (tier != null) {
                tierCounts[tier]--;
            }
        }
    }

    private void evict(int tier) {
        while (tierCounts[tier] > TIER_CAPACITY[tier]) {
            String eldest = null;
            for (String key : cache.keySet()) {
                Integer holder = tiers.get(key);
                if (holder != null && holder == tier) {
                    eldest = key;
                    break;
                }
            }
            if (eldest == null) {
                return;
            }
            removeCache(eldest);
            evictedTotal++;
        }
    }

    private int tierOf(String dimension, int cx, int cz) {
        if (dimension == null) {
            return TIER_C;
        }
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

    private void writeToDisk(String key, UnitSnapshot unit) {
        if (!KingdomPersistence.isReady()) {
            return;
        }
        byte[] data = unit.serialize();
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
                bytes += 1050L + unit.standCount() * 4L + unit.blockCount() * 2L;
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
                + "  累计构建=" + total + "  累计请求=" + requested + "  累计逐出=" + evicted);
        lines.add("缓存单元=" + units + "（A " + tiers[0] + " / B " + tiers[1] + " / C " + tiers[2]
                + "）  待建=" + pendingSize + "（紧急 " + urgentSize + " / 后台 " + backgroundSize
                + "）  标脏=" + dirtySize + "  密封=" + sealedSize);
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

    private List<String> collectWindow(MinecraftServer server) {
        List<String> keys = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            int minLayer = level.getMinBuildHeight() >> 4;
            int maxLayer = (level.getMaxBuildHeight() - 1) >> 4;
            level.players().forEach(player -> {
                int playerChunkX = player.getBlockX() >> 4;
                int playerChunkZ = player.getBlockZ() >> 4;
                for (int dx = -PLAYER_WINDOW_CHUNKS; dx <= PLAYER_WINDOW_CHUNKS; dx++) {
                    for (int dz = -PLAYER_WINDOW_CHUNKS; dz <= PLAYER_WINDOW_CHUNKS; dz++) {
                        int cx = playerChunkX + dx;
                        int cz = playerChunkZ + dz;
                        if (!level.hasChunk(cx, cz) || tierOf(dimension, cx, cz) == TIER_A) {
                            continue;
                        }
                        for (int layer = minLayer; layer <= maxLayer; layer++) {
                            String unitKey = UnitKey.of(dimension, cx, layer, cz);
                            if (sealed.contains(unitKey)) {
                                continue;
                            }
                            keys.add(unitKey);
                        }
                    }
                }
            });
        }
        return keys;
    }
}
