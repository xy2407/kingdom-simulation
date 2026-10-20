package com.xy2407.kingdom.server.path;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    private static final int BUILD_PER_TICK = 8;
    private static final long DIRTY_COOLDOWN_TICKS = 4L;
    private static final int PLAYER_WINDOW_CHUNKS = 4;
    private static final int WINDOW_SCAN_INTERVAL = 100;
    private static final int DEFAULT_WINDOW_LAYERS = 4;

    private final Map<String, UnitSnapshot> cache = new LinkedHashMap<>(256, 0.75F, true);
    private final Map<String, Integer> tiers = new HashMap<>();
    private final int[] tierCounts = new int[3];
    private final Set<String> pending = new LinkedHashSet<>();
    private final Deque<String> pendingOrder = new ArrayDeque<>();
    private final Set<String> diskKnown = new HashSet<>();
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
            pendingOrder.clear();
            diskKnown.clear();
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
            if (diskKnown.contains(key)) {
                request(dimension, cx, layer, cz);
                return null;
            }
        }
        UnitSnapshot loaded = readFromDisk(key);
        synchronized (this) {
            diskKnown.add(key);
            if (loaded != null) {
                persisted.add(key);
                putCache(dimension, cx, cz, key, loaded);
                return loaded;
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
        String key = UnitKey.of(dimension, cx, layer, cz);
        synchronized (this) {
            if (cache.containsKey(key) || pending.contains(key)) {
                return;
            }
            pending.add(key);
            pendingOrder.add(key);
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
            while (buildNow.size() < BUILD_PER_TICK && !pendingOrder.isEmpty()) {
                String key = pendingOrder.poll();
                pending.remove(key);
                buildNow.add(key);
            }
            rebuildNow = dirty.isEmpty() ? List.of() : new ArrayList<>(dirty);
            dirty.clear();
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
    }

    private void requestKey(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return;
        }
        try {
            request(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
        }
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

    private UnitSnapshot readFromDisk(String key) {
        if (!KingdomPersistence.isReady()) {
            return null;
        }
        try {
            return KingdomPersistence.querySync(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT " + SnapshotUnitSchema.COL_DATA + ", " + SnapshotUnitSchema.COL_FORMAT
                                + " FROM " + SnapshotUnitSchema.TABLE_SNAPSHOT
                                + " WHERE " + SnapshotUnitSchema.COL_KEY + " = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        return UnitSnapshot.deserialize(rs.getBytes(1), rs.getInt(2));
                    }
                }
            });
        } catch (Exception failure) {
            return null;
        }
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
        int dirtySize;
        int sealedSize;
        int diskKnownSize;
        int[] tiers;
        synchronized (this) {
            units = cache.size();
            pendingSize = pending.size();
            dirtySize = dirty.size();
            sealedSize = sealed.size();
            diskKnownSize = diskKnown.size();
            tiers = new int[] {tierCounts[0], tierCounts[1], tierCounts[2]};
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
        lines.add("缓存单元=" + units + "（A " + tiers[0] + " / B " + tiers[1] + " / C " + tiers[2]
                + "）  待建=" + pendingSize + "  标脏=" + dirtySize + "  密封=" + sealedSize
                + "  盘上已知=" + diskKnownSize);
        lines.add(String.format("估算内存≈%.1f MB", bytes / 1048576.0D));
        for (Map.Entry<String, int[]> entry : ranges.entrySet()) {
            int[] r = entry.getValue();
            lines.add("  " + entry.getKey() + "  区块x[" + r[0] + "," + r[1] + "] y层[" + r[2]
                    + "," + r[3] + "] z[" + r[4] + "," + r[5] + "]  覆盖 "
                    + ((r[1] - r[0] + 1) * 16) + "x" + ((r[3] - r[2] + 1) * 16) + "x"
                    + ((r[5] - r[4] + 1) * 16) + " 方块");
        }
        return lines;
    }

    private List<String> collectWindow(MinecraftServer server) {        List<String> keys = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            int[] window = yWindows.get(dimension);
            int minLayer = Math.max(level.getMinBuildHeight() >> 4,
                    window == null ? Integer.MIN_VALUE : window[0]);
            int maxLayer = Math.min((level.getMaxBuildHeight() - 1) >> 4,
                    window == null ? Integer.MAX_VALUE : window[1]);
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
