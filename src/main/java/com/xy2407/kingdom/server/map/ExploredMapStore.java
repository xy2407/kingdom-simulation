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
 *
 * <h2>为什么必须有它</h2>
 * 采样只认"此刻已加载的区块"——{@code hasChunk} 为假就记未探索。而区块会随玩家走远卸载，
 * 于是<B>走过的地方回头全成了空白</b>，地图的取景等于被锁死在采样那一刻的立足点附近：
 * 打开界面在市政厅旁边，看到的就是市政厅周围那一块，往外拖先是空白、再是黑。
 * 把每个区块采到的结果落盘之后，区块在不在就与地图无关了。
 *
 * <h2>按玩家分表</h2>
 * 同一维度里，<b>每个玩家各有一份地块表</b>（key 是他的 UUID）。所以 A 走出来的地图
 * 不会出现在 B 的地图上——每个人看到的都是他自己探索过的那片世界。存储上是
 * 一份 {@code SavedData}（按维度），内部按 UUID 分表。
 *
 * <h2>数据从两条路进来</h2>
 * <ol>
 *   <li>在线玩家周围的地面，每 {@value #SCAN_INTERVAL_TICKS} tick 扫一次、分帧采样入库。
 *       这是"走到就记"的来源。刻意<b>不用 {@code ChunkEvent.Load}</b>：那个事件只说明
 *       "某个区块加载了"，<b>不知道是哪个玩家造成的</b>，而这份数据要按玩家分开——
 *       归属错一次，别人的足迹就跑到你地图上了。改成按玩家位置扫，归属天然明确；</li>
 *   <li>地图界面的区域请求（见 {@link WorldMapServer}）打回来的整片采样，按区块并进
 *       <b>请求者自己的</b>表里，顺带把这次没加载到的部分用他自己的历史数据补上。</li>
 * </ol>
 *
 * <h2>两条线程约定</h2>
 * <ul>
 *   <li><b>入库数据 {@link Data} 可以在异步线程读写</b>：内部是 {@code ConcurrentHashMap}，
 *       而区域请求的采样本来就在线程池里跑（见 {@link WorldMapServer}）。取 {@code Data}
 *       实例这一步必须留在主线程（{@code getDataStorage()} 不是线程安全的）。</li>
 *   <li><b>待采队列只在主线程碰</b>：入队来自服务端 tick，推进也在 tick 里。</li>
 * </ul>
 */
public final class ExploredMapStore {

    /** 每区块的格子数。 */
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    /** 每 tick 最多采几个区块。一块 256 列、带高度图快速路径，这个量不会顶到主线程。 */
    private static final int CHUNKS_PER_TICK = 4;

    /** 待采队列上限，超了丢最旧的——地图数据不是关键路径，丢了下次扫到还会再入队。 */
    private static final int PENDING_CAP = 16384;

    /** 每个玩家最多记多少区块（每维度）：32768 块 ≈ 2896×2896 格，一个人跑不出这个范围。 */
    private static final int MAX_CHUNKS_PER_PLAYER = 32768;

    /** 玩家周围这个半径（区块）内的地面算"他到过"。13×13 个区块 ≈ 208×208 格。 */
    private static final int PLAYER_SCAN_RADIUS = 6;

    /** 每隔这么多 tick 扫一次在线玩家周围。40 tick = 2 秒，比玩家走得快得多。 */
    private static final int SCAN_INTERVAL_TICKS = 40;

    /** 待采队列，按维度分，只在服务端主线程读写。 */
    private static final Map<ResourceKey<Level>, Store> QUEUES = new ConcurrentHashMap<>();

    private ExploredMapStore() {
    }

    /* ------------------------------------------------------------------ 取仓库 */

    /**
     * 取某个维度的已探索数据。<b>只在主线程调用</b>——{@code getDataStorage()} 不是线程安全的，
     * 异步采样要用就先在这里取好，再把返回值传进去。
     */
    public static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data.FACTORY, Data.DATA_NAME);
    }

    /* ------------------------------------------------------------------ 区域请求路径 */

    /**
     * 把一次区域采样按区块并进<b>某个玩家</b>的表（可在异步线程调用，{@code data} 须已在
     * 主线程取好）。
     *
     * <p>只写"采到了"的格子：空白格保持原样，免得把上一趟采到的地形擦掉。落盘由调用方
     * 通过 {@link Data#setDirty()} 触发。</p>
     *
     * @param owner   这些地面记到谁名下
     * @param data    已探索数据（主线程取好）
     * @param colors  区域采样结果，行优先，{@code z * size + x}
     * @param originX 区域左上角世界 X
     * @param originZ 区域左上角世界 Z
     * @param size    区域边长
     * @return 是否真的写进了新内容
     */
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
                    continue; // 到上限了
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
                            continue; // 没采到：不覆盖已有的记录
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

    /**
     * 用<b>某个玩家</b>的历史数据补上这次采样没覆盖到的格子（就地改 {@code colors}）。
     *
     * <p>这是"能看见走过的地方"的关键一步：本次请求里已经卸载的区块采不到，但他自己表里
     * 有上一次路过时的记录，补进去地图就不再是空白。</p>
     */
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
                            continue; // 这次真采到了，以新数据为准
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

    /* ------------------------------------------------------------------ 逐块采样 */

    /**
     * 每 tick 推进：先周期性把在线玩家周围<b>还没记过</b>的区块排进队列，再按预算采几块入库。
     *
     * <p>"还没记过"这一步很关键——不加它的话，每 2 秒都会把玩家周围 169 个区块重新采一遍，
     * 纯属白烧。地形被玩家改造后的更新走的是区域请求那条路（{@link #record} 会覆盖旧值）。</p>
     */
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
                        continue; // 已经记过，不必重采
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
                continue; // 已经卸载了：那块等他下次站过去再说
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

    /** 换存档时清空待采队列。落盘数据归存档自己管（换档后 {@code getDataStorage()} 就是新的）。 */
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

    /** 一个待采区块：属于哪个玩家、哪个区块。 */
    private record Pending(UUID owner, long chunk) {
    }

    /** 待采队列。只在主线程读写，无需并发容器。 */
    private static final class Store {
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private final Set<Pending> queued = new HashSet<>();
    }

    /* ------------------------------------------------------------------ 落盘数据 */

    /**
     * 一个维度里所有玩家的已探索地表，按 UUID 分表、表内按区块保存。
     *
     * <p>每区块固定 {@value #CHUNK_SIDE}×{@value #CHUNK_SIDE} 个格子，每格一字节，存的是
     * 原版地图的<b>打包 id</b>（{@code MapColor.getPackedId}）。取用时务必 {@code & 0xFF}——
     * 打包 id 值域是 0~255，byte 是有符号的。</p>
     *
     * <p><b>0 表示"这里没有记录"</b>（也正好是 {@code MapColor.NONE} 的打包 id，两者在地图上
     * 都是黑，不必区分），所以填充与补白的判据统一按 0 走。</p>
     *
     * <p>序列化成一个 {@code ListTag}，每项是一个玩家：UUID + 一对平行数组
     * （{@code long[]} 区块 id + {@code byte[]} 像素）。比逐块存子标签省得多。</p>
     */
    public static final class Data extends SavedData {
        private static final String DATA_NAME = "kingdom_explored_map";
        private static final String KEY_PLAYERS = "Players";
        private static final String KEY_ID = "Id";
        private static final String KEY_CHUNKS = "Chunks";
        private static final String KEY_PIXELS = "Pixels";

        static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load, null);

        /** key = 玩家 UUID；value = 他一个人的地块表。 */
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

        /** 某个玩家已记下的区块数，诊断用。 */
        public int chunkCount(UUID owner) {
            PlayerMap map = players.get(owner);
            return map == null ? 0 : map.cells.size();
        }

        /**
         * 某个玩家那份数据的内容版本：他有新内容写进来就往前走一格。
         *
         * <p>客户端拿它当"我手上这份是哪一版"的凭据——请求时带回来，服务端一比就知道
         * 要不要重发整块。没有它就只能定期把整块重传一遍，那才是白烧带宽。</p>
         *
         * <p><b>按玩家分开</b>：每个人地图不同，版本自然也要各算各的。</p>
         *
         * <p>刻意不落盘：重启后归零，客户端带来的旧版本号自然对不上，于是会拉一次全量，
         * 正是想要的。</p>
         */
        public int revision(UUID owner) {
            PlayerMap map = players.get(owner);
            return map == null ? 0 : map.revision;
        }

        /** 某个玩家有实质写入时调用，让他那份的版本号往前走一格。 */
        public void bumpRevision(UUID owner) {
            PlayerMap map = players.get(owner);
            if (map != null) {
                map.revision++;
            }
        }

        /** 取某个玩家某区块的格子数据；没有记录返回 {@code null}。 */
        byte[] cellOrNull(UUID owner, int chunkX, int chunkZ) {
            PlayerMap map = players.get(owner);
            return map == null ? null : map.cells.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        /**
         * 取某个玩家某区块的格子数据，没有就新建一个。
         * 到达该玩家的上限、且该区块是新的时候返回 {@code null}。
         */
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

        /** 一个玩家的地块表与内容版本。 */
        private static final class PlayerMap {
            private final Map<Long, byte[]> cells = new ConcurrentHashMap<>();
            private int revision;
        }
    }
}
