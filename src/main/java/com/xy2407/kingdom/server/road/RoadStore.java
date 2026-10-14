package com.xy2407.kingdom.server.road;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayDataPacket;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RoadSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * 道路的内存索引与落库：领地道路按格（road_cell）、世界道路按区块（road_chunk，含 cells 与 owner），
 * 供铺路去重、地图叠层下发与传送校验读取。
 */
public final class RoadStore {

    public static final RoadStore INSTANCE = new RoadStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<String, Set<Long>> cellsByDimension = new HashMap<>();
    private final Map<String, Map<Long, RoadChunkData>> chunksByDimension = new HashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    // 世界道路的一个区块：铺好的格（BlockPos.asLong，含真实地表 y）+ 提交者（D3 传送校验用）
    record RoadChunkData(long[] cells, String owner) {
    }

    private RoadStore() {
    }

    public void reset() {
        cellsByDimension.clear();
        chunksByDimension.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public void tick() {
        if (loaded) {
            return;
        }
        if (loadRetryCooldown > 0) {
            loadRetryCooldown--;
            return;
        }
        if (KingdomPersistence.isReady()) {
            load();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean contains(String dimension, BlockPos pos) {
        Set<Long> cells = cellsByDimension.get(dimension);
        return cells != null && cells.contains(pos.asLong());
    }

    public int size() {
        int total = 0;
        for (Set<Long> cells : cellsByDimension.values()) {
            total += cells.size();
        }
        return total;
    }

    public void record(String dimension, BlockPos pos, String territoryId, String owner, long day) {
        cellsByDimension.computeIfAbsent(dimension, key -> new HashSet<>()).add(pos.asLong());
        String key = RoadSchema.cellKey(dimension, pos.getX(), pos.getY(), pos.getZ());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RoadSchema.COL_CELL_KEY, key);
        values.put(RoadSchema.COL_DIMENSION, dimension);
        values.put(RoadSchema.COL_X, pos.getX());
        values.put(RoadSchema.COL_Y, pos.getY());
        values.put(RoadSchema.COL_Z, pos.getZ());
        values.put(RoadSchema.COL_TERRITORY_ID, territoryId == null ? "" : territoryId);
        values.put(RoadSchema.COL_OWNER, owner == null ? "" : owner);
        values.put(RoadSchema.COL_UPDATED_AT, day);
        KingdomPersistence.submit(RoadSchema.TABLE_CELL, RoadSchema.COL_CELL_KEY, key, values);
    }

    // 世界道路按区块聚合落库（W13：同区块后写覆盖先写，主键 upsert 天然成立）；
    // cells 编码 = 每格 3 字节：localX(4bit) | localZ(4bit) | y(12bit)，见 §7.1
    public void recordChunk(String dimension, int chunkX, int chunkZ, long[] cellPositions,
                            String owner, long day) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        chunksByDimension.computeIfAbsent(dimension, key -> new HashMap<>())
                .put(chunkKey, new RoadChunkData(cellPositions.clone(), owner));
        byte[] blob = encodeChunkCells(chunkX, chunkZ, cellPositions);
        String key = RoadSchema.chunkKey(dimension, chunkX, chunkZ);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RoadSchema.COL_CHUNK_KEY, key);
        values.put(RoadSchema.COL_DIMENSION, dimension);
        values.put(RoadSchema.COL_CHUNK_X, chunkX);
        values.put(RoadSchema.COL_CHUNK_Z, chunkZ);
        values.put(RoadSchema.COL_CELLS, blob);
        values.put(RoadSchema.COL_OWNER, owner == null ? "" : owner);
        values.put(RoadSchema.COL_UPDATED_AT, day);
        KingdomPersistence.submit(RoadSchema.TABLE_CHUNK, RoadSchema.COL_CHUNK_KEY, key, values);
    }

    // 路图构建的数据源（P1）：只读视图，调用方不得修改；键 = BlockPos.asLong（道路格，含 y）
    public Set<Long> roadCells(String dimension) {
        Set<Long> cells = cellsByDimension.get(dimension);
        return cells == null ? Set.of() : cells;
    }

    public boolean hasRoadChunk(String dimension, int chunkX, int chunkZ) {
        Map<Long, RoadChunkData> chunks = chunksByDimension.get(dimension);
        return chunks != null && chunks.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    public String roadOwner(String dimension, int chunkX, int chunkZ) {
        Map<Long, RoadChunkData> chunks = chunksByDimension.get(dimension);
        if (chunks == null) {
            return "";
        }
        RoadChunkData data = chunks.get(ChunkPos.asLong(chunkX, chunkZ));
        return data == null ? "" : data.owner();
    }

    // W14：视野内的道路区块（含格集），供 MapOverlayCollector 下发；超出 limit 按到视野中心的距离取最近
    public List<MapOverlayDataPacket.RoadChunkEntry> roadChunksIn(String dimension,
                                                                  int minChunkX, int maxChunkX,
                                                                  int minChunkZ, int maxChunkZ,
                                                                  int centerChunkX, int centerChunkZ,
                                                                  int limit) {
        Map<Long, RoadChunkData> chunks = chunksByDimension.get(dimension);
        List<MapOverlayDataPacket.RoadChunkEntry> result = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return result;
        }
        List<long[]> scored = new ArrayList<>();
        for (Map.Entry<Long, RoadChunkData> entry : chunks.entrySet()) {
            int chunkX = ChunkPos.getX(entry.getKey());
            int chunkZ = ChunkPos.getZ(entry.getKey());
            if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                continue;
            }
            int dx = chunkX - centerChunkX;
            int dz = chunkZ - centerChunkZ;
            scored.add(new long[] {entry.getKey(), (long) dx * dx + (long) dz * dz});
        }
        scored.sort((a, b) -> Long.compare(a[1], b[1]));
        for (int index = 0; index < scored.size() && result.size() < limit; index++) {
            long key = scored.get(index)[0];
            RoadChunkData data = chunks.get(key);
            if (data == null || data.cells().length == 0) {
                continue;
            }
            result.add(new MapOverlayDataPacket.RoadChunkEntry(
                    ChunkPos.getX(key), ChunkPos.getZ(key), data.cells()));
        }
        return result;
    }

    private static byte[] encodeChunkCells(int chunkX, int chunkZ, long[] cellPositions) {
        byte[] blob = new byte[cellPositions.length * 3];
        for (int index = 0; index < cellPositions.length; index++) {
            BlockPos pos = BlockPos.of(cellPositions[index]);
            int localX = pos.getX() - (chunkX << 4);
            int localZ = pos.getZ() - (chunkZ << 4);
            blob[index * 3] = (byte) ((localX & 0x0F) | ((localZ & 0x0F) << 4));
            int y = pos.getY();
            blob[index * 3 + 1] = (byte) (y & 0xFF);
            blob[index * 3 + 2] = (byte) ((y >> 8) & 0x0F);
        }
        return blob;
    }

    private static long[] decodeChunkCells(int chunkX, int chunkZ, byte[] blob) {
        if (blob == null || blob.length % 3 != 0) {
            return new long[0];
        }
        long[] cells = new long[blob.length / 3];
        for (int index = 0; index < cells.length; index++) {
            int localX = blob[index * 3] & 0x0F;
            int localZ = (blob[index * 3] >> 4) & 0x0F;
            int y = (blob[index * 3 + 1] & 0xFF) | ((blob[index * 3 + 2] & 0x0F) << 8);
            cells[index] = BlockPos.asLong((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        }
        return cells;
    }

    private void load() {
        try {
            List<String[]> rows = KingdomPersistence.querySync(connection -> {
                List<String[]> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + RoadSchema.columnsCell()
                             + " FROM " + RoadSchema.TABLE_CELL)) {
                    while (rs.next()) {
                        result.add(new String[] {
                                rs.getString(RoadSchema.COL_DIMENSION),
                                String.valueOf(rs.getInt(RoadSchema.COL_X)),
                                String.valueOf(rs.getInt(RoadSchema.COL_Y)),
                                String.valueOf(rs.getInt(RoadSchema.COL_Z))
                        });
                    }
                }
                return result;
            });
            cellsByDimension.clear();
            for (String[] row : rows) {
                long packed = BlockPos.asLong(Integer.parseInt(row[1]), Integer.parseInt(row[2]),
                        Integer.parseInt(row[3]));
                cellsByDimension.computeIfAbsent(row[0], key -> new HashSet<>()).add(packed);
            }
            loadRoadChunks();
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 个领地道路方块", size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载领地道路失败", exception);
        }
    }

    // road_chunk 回读：重启后传送校验（D3 owner）与地图叠层下发都靠这份内存索引
    private void loadRoadChunks() {
        List<Object[]> rows;
        try {
            rows = KingdomPersistence.querySync(connection -> {
                List<Object[]> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + RoadSchema.columnsChunk()
                             + " FROM " + RoadSchema.TABLE_CHUNK)) {
                    while (rs.next()) {
                        result.add(new Object[] {
                                rs.getString(RoadSchema.COL_DIMENSION),
                                rs.getInt(RoadSchema.COL_CHUNK_X),
                                rs.getInt(RoadSchema.COL_CHUNK_Z),
                                rs.getBytes(RoadSchema.COL_CELLS),
                                rs.getString(RoadSchema.COL_OWNER)
                        });
                    }
                }
                return result;
            });
        } catch (Exception exception) {
            ModMain.LOGGER.error("加载世界道路（road_chunk）失败，传送与地图道路层暂不可用", exception);
            return;
        }
        chunksByDimension.clear();
        for (Object[] row : rows) {
            String dimension = (String) row[0];
            int chunkX = (Integer) row[1];
            int chunkZ = (Integer) row[2];
            byte[] blob = (byte[]) row[3];
            String owner = (String) row[4];
            chunksByDimension.computeIfAbsent(dimension, key -> new HashMap<>())
                    .put(ChunkPos.asLong(chunkX, chunkZ),
                            new RoadChunkData(decodeChunkCells(chunkX, chunkZ, blob), owner));
        }
    }
}
