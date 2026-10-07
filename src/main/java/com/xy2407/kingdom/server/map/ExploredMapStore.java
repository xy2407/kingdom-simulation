package com.xy2407.kingdom.server.map;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.map.WorldSurfaceSampler;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「已探索地图」仓库：把玩家走到过的区块地表按区块存下来，并能把它们拼回任意区域。
 *
 * <h2>为什么必须有它</h2>
 * 采样只认"此刻已加载的区块"——{@code hasChunk} 为假就记未探索。而区块会随玩家走远卸载，
 * 于是<B>走过的地方回头全成了空白</b>，地图的取景等于被锁死在采样那一刻的立足点附近：
 * 打开界面在市政厅旁边，看到的就是市政厅周围那一块，往外拖先是空白、再是黑。
 * 把每个区块采到的结果落盘之后，区块在不在就与地图无关了。
 *
 * <h2>数据从两条路进来</h2>
 * <ol>
 *   <li>{@code ChunkEvent.Load}——玩家所到之处逐块入队，分帧采样入库。这是"探索过就记得"
 *       的来源，也是与"只是在界面里拖了拖"的区别；</li>
 *   <li>地图界面的区域请求（{@link WorldMapServer}）打回来的整片采样，按区块并进来，
 *       顺带把这次没加载到的部分用历史数据补上。</li>
 * </ol>
 *
 * <h2>两条线程约定</h2>
 * <ul>
 *   <li><b>入库数据 {@link Data} 可以在异步线程读写</b>：内部是 {@code ConcurrentHashMap}，
 *       而区域请求的采样本来就在线程池里跑（见 {@link WorldMapServer}）。取 {@code Data}
 *       实例这一步必须留在主线程（{@code getDataStorage()} 不是线程安全的）。</li>
 *   <li><b>待采队列只在主线程碰</b>：入队来自区块事件，推进来自服务端 tick。</li>
 * </ul>
 */
public final class ExploredMapStore {

    /** 每区块的格子数。 */
    private static final int CHUNK_SIDE = 16;
    private static final int CHUNK_CELLS = CHUNK_SIDE * CHUNK_SIDE;

    /** 每 tick 最多采几个区块。一块 256 列、带高度图快速路径，这个量不会顶到主线程。 */
    private static final int CHUNKS_PER_TICK = 4;

    /** 待采队列上限，超了丢最旧的——地图数据不是关键路径，丢了下次走到还会再入队。 */
    private static final int PENDING_CAP = 8192;

    /** 单维度最多记多少区块：32768 块 ≈ 2896×2896 格，一个王国的活动范围够用。 */
    private static final int MAX_CHUNKS = 32768;

    /** 待采队列，只在服务端主线程读写。 */
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

    /* ------------------------------------------------------------------ 入库 */

    /**
     * 把一次区域采样按区块并进仓库（可在异步线程调用，{@code data} 必须已在主线程取好）。
     *
     * <p>只写"采到了"的格子：空白格保持原样，免得把上一趟采到的地形擦掉。落盘由调用方
     * 通过 {@link Data#setDirty()} 触发。</p>
     *
     * @param data    已探索数据（主线程取好）
     * @param colors  区域采样结果，行优先，{@code z * size + x}
     * @param originX 区域左上角世界 X
     * @param originZ 区域左上角世界 Z
     * @param size    区域边长
     * @return 是否真的写进了新内容
     */
    public static boolean record(Data data, int[] colors, int originX, int originZ, int size) {
        if (data == null || colors == null || size <= 0) {
            return false;
        }
        boolean changed = false;
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                byte[] cell = data.cellForWrite(chunkX, chunkZ);
                if (cell == null) {
                    continue; // 到上限了
                }
                boolean cellChanged = false;
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
                            continue; // 没采到：不覆盖仓库里已有的记录
                        }
                        byte stored = (byte) packed;
                        int index = z * CHUNK_SIDE + x;
                        if (cell[index] != stored) {
                            cell[index] = stored;
                            cellChanged = true;
                        }
                    }
                }
                changed |= cellChanged;
            }
        }
        if (changed) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * 用仓库里的历史数据补上这次采样没覆盖到的格子（就地改 {@code colors}）。
     *
     * <p>这是"能看见走过的地方"的关键一步：本次请求里已经卸载的区块采不到，但仓库里有
     * 上一次路过时的记录，补进去地图就不再是空白。</p>
     */
    public static void fillGaps(Data data, int[] colors, int originX, int originZ, int size) {
        if (data == null || colors == null || size <= 0) {
            return;
        }
        int minChunkX = originX >> 4;
        int maxChunkX = (originX + size - 1) >> 4;
        int minChunkZ = originZ >> 4;
        int maxChunkZ = (originZ + size - 1) >> 4;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                byte[] cell = data.cellOrNull(chunkX, chunkZ);
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

    /* ------------------------------------------------------------------ 逐块入队 */

    /** 区块加载时入队（主线程，O(1)；真正耗时的扫描在 {@link #tick} 里分帧做）。 */
    public static void enqueue(ServerLevel level, ChunkPos pos) {
        offer(queue(level.dimension()), pos.toLong());
    }

    /** 每 tick 推进一小段逐块采样。 */
    public static void tick(ServerLevel level) {
        Store queue = queue(level.dimension());
        if (queue.pending.isEmpty()) {
            return;
        }
        Data data = data(level);
        if (data == null) {
            return;
        }
        int budget = CHUNKS_PER_TICK;
        int scan = queue.pending.size();
        boolean changed = false;
        while (budget > 0 && scan-- > 0) {
            Long id = queue.pending.poll();
            if (id == null) {
                break;
            }
            queue.pendingIds.remove(id);
            ChunkPos pos = new ChunkPos(id);
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) {
                continue; // 已经卸载了：那块等它下次加载再入队
            }
            int[] colors = WorldSurfaceSampler.sampleChunk(level, pos.x, pos.z);
            byte[] cell = data.cellForWrite(pos.x, pos.z);
            if (cell == null) {
                continue;
            }
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
            changed |= cellChanged;
            budget--;
        }
        if (changed) {
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

    private static void offer(Store store, long id) {
        if (!store.pendingIds.add(id)) {
            return;
        }
        if (store.pending.size() >= PENDING_CAP) {
            store.pendingIds.remove(store.pending.poll());
        }
        store.pending.add(id);
    }

    /** 待采队列。只在主线程读写，无需并发容器。 */
    private static final class Store {
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        private final Set<Long> pendingIds = new HashSet<>();
    }

    /* ------------------------------------------------------------------ 落盘数据 */

    /**
     * 一个维度的已探索地表，按区块保存。
     *
     * <p>每区块固定 {@value #CHUNK_SIDE}×{@value #CHUNK_SIDE} 个格子，每格一字节，存的是
     * 原版地图的<b>打包 id</b>（{@code MapColor.getPackedId}）。取用时务必 {@code & 0xFF}——
     * 打包 id 值域是 0~255，byte 是有符号的。</p>
     *
     * <p><b>0 表示"这里没有记录"</b>（也正好是 {@code MapColor.NONE} 的打包 id，两者在地图上
     * 都是黑，不必区分），所以填充与补白的判据统一按 0 走。</p>
     *
     * <p>序列化成一对平行数组（{@code long[]} 区块 id + {@code byte[]} 像素），比逐块存
     * 子标签省得多。</p>
     */
    public static final class Data extends SavedData {
        private static final String DATA_NAME = "kingdom_explored_map";
        private static final String KEY_CHUNKS = "Chunks";
        private static final String KEY_PIXELS = "Pixels";

        static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load, null);

        /** key = {@code ChunkPos.asLong}；value = 每区块 {@value #CHUNK_CELLS} 个字节。 */
        private final Map<Long, byte[]> cells = new ConcurrentHashMap<>();

        private Data() {
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data data = new Data();
            long[] ids = tag.getLongArray(KEY_CHUNKS);
            byte[] pixels = tag.getByteArray(KEY_PIXELS);
            for (int i = 0; i < ids.length; i++) {
                int from = i * CHUNK_CELLS;
                if (from + CHUNK_CELLS > pixels.length) {
                    break;
                }
                byte[] cell = new byte[CHUNK_CELLS];
                System.arraycopy(pixels, from, cell, 0, CHUNK_CELLS);
                data.cells.put(ids[i], cell);
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            int count = cells.size();
            long[] ids = new long[count];
            byte[] pixels = new byte[count * CHUNK_CELLS];
            int i = 0;
            for (Map.Entry<Long, byte[]> entry : cells.entrySet()) {
                ids[i] = entry.getKey();
                System.arraycopy(entry.getValue(), 0, pixels, i * CHUNK_CELLS, CHUNK_CELLS);
                i++;
            }
            tag.putLongArray(KEY_CHUNKS, ids);
            tag.putByteArray(KEY_PIXELS, pixels);
            return tag;
        }

        /** 已记下的区块数，诊断用。 */
        public int chunkCount() {
            return cells.size();
        }

        /** 取某区块的格子数据；没有记录返回 {@code null}。 */
        byte[] cellOrNull(int chunkX, int chunkZ) {
            return cells.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        /** 取某区块的格子数据，没有就新建一个。到达上限且该区块是新的时候返回 {@code null}。 */
        byte[] cellForWrite(int chunkX, int chunkZ) {
            long id = ChunkPos.asLong(chunkX, chunkZ);
            byte[] existing = cells.get(id);
            if (existing != null) {
                return existing;
            }
            if (cells.size() >= MAX_CHUNKS) {
                return null;
            }
            byte[] created = new byte[CHUNK_CELLS];
            byte[] raced = cells.putIfAbsent(id, created);
            return raced != null ? raced : created;
        }
    }
}
