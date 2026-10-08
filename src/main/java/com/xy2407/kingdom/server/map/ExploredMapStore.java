package com.xy2407.kingdom.server.map;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「已探索地图」仓库：按玩家记录他到过的区块地表，并能把它们拼回任意区域。
 */
public final class ExploredMapStore {

    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    private static final int CHUNKS_PER_TICK = 4;

    private static final int PENDING_CAP = 16384;

    private static final int MAX_CHUNKS_PER_PLAYER = 32768;

    private static final int PLAYER_SCAN_RADIUS = 6;

    private static final int SCAN_INTERVAL_TICKS = 40;

    private static final Map<ResourceKey<Level>, Store> QUEUES = new ConcurrentHashMap<>();

    private ExploredMapStore() {
    }


    public static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data.FACTORY, Data.DATA_NAME);
    }


    public static boolean record(UUID owner, Data data, int[] colors,
                                 int originX, int originZ, int size) {
        if (owner == null || data == null || colors == null || size <= 0) {
            return false;
        }
        boolean changed = false;
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                byte[] cell = data.cellForWrite(owner, chunkX, chunkZ);
                if (cell == null) {
                    continue;
                }
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                for (int z = 0; z < CHUNK_SIDE; z++) {
                    int worldZ = baseZ + z;
                    int dz = worldZ - originZ;
                    if (dz < 0 || dz >= size) {
                        continue;
                    }
                    for (int x = 0; x < CHUNK_SIDE; x++) {
                        int worldX = baseX + x;
                        int dx = worldX - originX;
                        if (dx < 0 || dx >= size) {
                            continue;
                        }
                        int packed = colors[dz * size + dx];
                        if (packed == WorldSurfaceSampler.UNEXPLORED) {
                            continue;
                        }
                        byte stored = (byte) packed;
                        int index = z * CHUNK_SIDE + x;
                        if (cell[index] != stored) {
                            cell[index] = stored;
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) {
            data.bumpRevision(owner);
            data.setDirty();
        }
        return changed;
    }

    public static void fillGaps(UUID owner, Data data, int[] colors,
                                int originX, int originZ, int size) {
        if (owner == null || data == null || colors == null || size <= 0) {
            return;
        }
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                byte[] cell = data.cellOrNull(owner, chunkX, chunkZ);
                if (cell == null) {
                    continue;
                }
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                for (int z = 0; z < CHUNK_SIDE; z++) {
                    int worldZ = baseZ + z;
                    int dz = worldZ - originZ;
                    if (dz < 0 || dz >= size) {
                        continue;
                    }
                    for (int x = 0; x < CHUNK_SIDE; x++) {
                        int worldX = baseX + x;
                        int dx = worldX - originX;
                        if (dx < 0 || dx >= size) {
                            continue;
                        }
                        int index = dz * size + dx;
                        if (colors[index] != WorldSurfaceSampler.UNEXPLORED) {
                            continue;
                        }
                        int stored = cell[z * CHUNK_SIDE + x] & 0xFF;
                        if (stored != 0) {
                            colors[index] = stored;
                        }
                    }
                }
            }
        }
    }


    public static void tick(ServerLevel level) {
        Data data = data(level);
        if (data == null) {
            return;
        }
        Store store = queue(level.dimension());

        if (level.getGameTime() % SCAN_INTERVAL_TICKS == 0L) {
            scanPlayers(level, store, data);
        }
        if (store.pending.isEmpty()) {
            return;
        }
        drain(level, store, data);
    }

    private static void scanPlayers(ServerLevel level, Store store, Data data) {
        for (ServerPlayer player : level.players()) {
            UUID owner = player.getUUID();
            ChunkPos center = player.chunkPosition();
            for (int dz = -PLAYER_SCAN_RADIUS; dz <= PLAYER_SCAN_RADIUS; dz++) {
                for (int dx = -PLAYER_SCAN_RADIUS; dx <= PLAYER_SCAN_RADIUS; dx++) {
                    int chunkX = center.x + dx;
                    int chunkZ = center.z + dz;
                    if (data.cellOrNull(owner, chunkX, chunkZ) != null) {
                        continue;
                    }
                    offer(store, new Pending(owner, ChunkPos.asLong(chunkX, chunkZ)));
                }
            }
        }
    }

    private static void drain(ServerLevel level, Store store, Data data) {
        int budget = CHUNKS_PER_TICK;
        int scan = store.pending.size();
        boolean dirty = false;
        while (budget > 0 && scan-- > 0) {
            Pending entry = store.pending.poll();
            if (entry == null) {
                break;
            }
            store.queued.remove(entry);
            ChunkPos pos = new ChunkPos(entry.chunk());
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) {
                continue;
            }
            byte[] cell = data.cellForWrite(entry.owner(), pos.x, pos.z);
            if (cell == null) {
                continue;
            }
            int[] colors = WorldSurfaceSampler.sampleChunk(level, pos.x, pos.z);
            boolean cellChanged = false;
            for (int i = 0; i < CHUNK_CELLS; i++) {
                int packed = colors[i];
                if (packed == WorldSurfaceSampler.UNEXPLORED) {
                    continue;
                }
                byte stored = (byte) packed;
                if (cell[i] != stored) {
                    cell[i] = stored;
                    cellChanged = true;
                }
            }
            if (cellChanged) {
                data.bumpRevision(entry.owner());
                dirty = true;
            }
            budget--;
        }
        if (dirty) {
            data.setDirty();
        }
    }

    public static void reset() {
        QUEUES.clear();
    }

    private static Store queue(ResourceKey<Level> dimension) {
        return QUEUES.computeIfAbsent(dimension, key -> new Store());
    }

    private static void offer(Store store, Pending entry) {
        if (!store.queued.add(entry)) {
            return;
        }
        if (store.pending.size() >= PENDING_CAP) {
            store.queued.remove(store.pending.poll());
        }
        store.pending.add(entry);
    }

    private record Pending(UUID owner, long chunk) {
    }

    private static final class Store {
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private final Set<Pending> queued = new HashSet<>();
    }


    public static final class Data extends SavedData {
        private static final String DATA_NAME = "kingdom_explored_map";
        private static final String KEY_PLAYERS = "Players";
        private static final String KEY_ID = "Id";
        private static final String KEY_CHUNKS = "Chunks";
        private static final String KEY_PIXELS = "Pixels";

        static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load, null);

        private final Map<UUID, PlayerMap> players = new ConcurrentHashMap<>();

        private Data() {
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data data = new Data();
            ListTag list = tag.getList(KEY_PLAYERS, Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag playerTag = list.getCompound(index);
                if (!playerTag.hasUUID(KEY_ID)) {
                    continue;
                }
                PlayerMap map = data.mapForWrite(playerTag.getUUID(KEY_ID));
                long[] ids = playerTag.getLongArray(KEY_CHUNKS);
                byte[] pixels = playerTag.getByteArray(KEY_PIXELS);
                for (int i = 0; i < ids.length; i++) {
                    int from = i * CHUNK_CELLS;
                    if (from + CHUNK_CELLS > pixels.length) {
                        break;
                    }
                    byte[] cell = new byte[CHUNK_CELLS];
                    System.arraycopy(pixels, from, cell, 0, CHUNK_CELLS);
                    map.cells.put(ids[i], cell);
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag list = new ListTag();
            for (Map.Entry<UUID, PlayerMap> entry : players.entrySet()) {
                PlayerMap map = entry.getValue();
                int count = map.cells.size();
                if (count == 0) {
                    continue;
                }
                long[] ids = new long[count];
                byte[] pixels = new byte[count * CHUNK_CELLS];
                int i = 0;
                for (Map.Entry<Long, byte[]> cell : map.cells.entrySet()) {
                    ids[i] = cell.getKey();
                    System.arraycopy(cell.getValue(), 0, pixels, i * CHUNK_CELLS, CHUNK_CELLS);
                    i++;
                }
                CompoundTag playerTag = new CompoundTag();
                playerTag.putUUID(KEY_ID, entry.getKey());
                playerTag.putLongArray(KEY_CHUNKS, ids);
                playerTag.putByteArray(KEY_PIXELS, pixels);
                list.add(playerTag);
            }
            tag.put(KEY_PLAYERS, list);
            return tag;
        }

        public int chunkCount(UUID owner) {
            PlayerMap map = players.get(owner);
            return map == null ? 0 : map.cells.size();
        }

        public int revision(UUID owner) {
            PlayerMap map = players.get(owner);
            return map == null ? 0 : map.revision;
        }

        public void bumpRevision(UUID owner) {
            PlayerMap map = players.get(owner);
            if (map != null) {
                map.revision++;
            }
        }

        byte[] cellOrNull(UUID owner, int chunkX, int chunkZ) {
            PlayerMap map = players.get(owner);
            return map == null ? null : map.cells.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        byte[] cellForWrite(UUID owner, int chunkX, int chunkZ) {
            PlayerMap map = mapForWrite(owner);
            long id = ChunkPos.asLong(chunkX, chunkZ);
            byte[] existing = map.cells.get(id);
            if (existing != null) {
                return existing;
            }
            if (map.cells.size() >= MAX_CHUNKS_PER_PLAYER) {
                return null;
            }
            byte[] created = new byte[CHUNK_CELLS];
            byte[] raced = map.cells.putIfAbsent(id, created);
            return raced != null ? raced : created;
        }

        private PlayerMap mapForWrite(UUID owner) {
            return players.computeIfAbsent(owner, key -> new PlayerMap());
        }

        private static final class PlayerMap {
            private final Map<Long, byte[]> cells = new ConcurrentHashMap<>();
            private int revision;
        }
    }
}
