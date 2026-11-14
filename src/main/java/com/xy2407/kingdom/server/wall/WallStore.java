package com.xy2407.kingdom.server.wall;

import java.io.ByteArrayOutputStream;
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
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.WallSchema;
import com.xy2407.kingdom.common.wall.WallBlock;
import com.xy2407.kingdom.common.wall.WallKind;
import com.xy2407.kingdom.common.wall.WallNode;
import com.xy2407.kingdom.common.wall.WallSegment;

import net.minecraft.core.BlockPos;

/**
 * 城墙内存索引与落盘：节点、墙段、逐格方块与按区块聚合四份数据，读只在启动、写走单写队列
 */
public final class WallStore {

    public static final WallStore INSTANCE = new WallStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<String, WallNode> nodes = new LinkedHashMap<>();
    private final Map<String, WallSegment> segments = new LinkedHashMap<>();
    private final Map<String, List<WallBlock>> blocks = new HashMap<>();
    private final Map<String, String> blockDimensions = new HashMap<>();
    private final Map<String, List<WallBlock>> chunks = new HashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private WallStore() {
    }

    public void reset() {
        nodes.clear();
        segments.clear();
        blocks.clear();
        blockDimensions.clear();
        chunks.clear();
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

    public WallNode node(String key) {
        return key == null ? null : nodes.get(key);
    }

    public WallSegment segment(String key) {
        return key == null ? null : segments.get(key);
    }

    public List<WallNode> allNodes() {
        return new ArrayList<>(nodes.values());
    }

    public List<WallSegment> allSegments() {
        return new ArrayList<>(segments.values());
    }

    public List<WallNode> nodesIn(String dimension) {
        List<WallNode> result = new ArrayList<>();
        for (WallNode node : nodes.values()) {
            if (node.dimension().equals(dimension)) {
                result.add(node);
            }
        }
        return result;
    }

    public List<WallSegment> segmentsIn(String dimension) {
        List<WallSegment> result = new ArrayList<>();
        for (WallSegment segment : segments.values()) {
            if (segment.dimension().equals(dimension)) {
                result.add(segment);
            }
        }
        return result;
    }

    public List<WallSegment> segmentsTouching(String nodeKey) {
        List<WallSegment> result = new ArrayList<>();
        for (WallSegment segment : segments.values()) {
            if (segment.key().contains("|" + nodeKey + "|") || segment.key().endsWith("|" + nodeKey)) {
                result.add(segment);
            }
        }
        return result;
    }

    public void recordNode(WallNode node) {
        nodes.put(node.key(), node);
        KingdomPersistence.submit(WallSchema.TABLE_NODE, WallSchema.COL_NODE_KEY,
                node.key(), nodeValues(node));
    }

    public void updateNode(WallNode node) {
        recordNode(node);
    }

    public void recordSegment(WallSegment segment) {
        segments.put(segment.key(), segment);
        KingdomPersistence.submit(WallSchema.TABLE_SEGMENT, WallSchema.COL_SEGMENT_KEY,
                segment.key(), segmentValues(segment));
    }

    public void updateSegment(WallSegment segment) {
        recordSegment(segment);
    }

    public void removeNode(String key) {
        nodes.remove(key);
        KingdomPersistence.delete(WallSchema.TABLE_NODE, WallSchema.COL_NODE_KEY, key);
    }

    public void removeSegment(String key) {
        segments.remove(key);
        KingdomPersistence.delete(WallSchema.TABLE_SEGMENT, WallSchema.COL_SEGMENT_KEY, key);
    }

    public void recordBlocks(String taskKey, String dimension, List<WallBlock> placed) {
        blocks.put(taskKey, List.copyOf(placed));
        blockDimensions.put(taskKey, dimension);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WallSchema.COL_TASK_KEY, taskKey);
        values.put(WallSchema.COL_DIMENSION, dimension);
        values.put(WallSchema.COL_BLOCKS, encodeCells(placed));
        values.put(WallSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(WallSchema.TABLE_BLOCKS, WallSchema.COL_TASK_KEY, taskKey, values);
        rebuildChunks(dimension, chunksOf(placed));
    }

    public List<WallBlock> blocksOf(String taskKey) {
        List<WallBlock> cached = blocks.get(taskKey);
        return cached == null ? List.of() : cached;
    }

    public void removeBlocks(String taskKey) {
        String dimension = blockDimensions.remove(taskKey);
        List<WallBlock> removed = blocks.remove(taskKey);
        KingdomPersistence.delete(WallSchema.TABLE_BLOCKS, WallSchema.COL_TASK_KEY, taskKey);
        if (dimension != null && removed != null) {
            rebuildChunks(dimension, chunksOf(removed));
        }
    }

    public List<WallBlock> chunkBlocks(String dimension, int chunkX, int chunkZ) {
        List<WallBlock> cached = chunks.get(WallSchema.chunkKey(dimension, chunkX, chunkZ));
        return cached == null ? List.of() : cached;
    }

    /**
     * 按方块精确反查归属任务（逐方块比对 wall_blocks 存储），未命中返回 null
     */
    public String taskAt(String dimension, int x, int y, int z) {
        for (Map.Entry<String, List<WallBlock>> entry : blocks.entrySet()) {
            if (!dimension.equals(blockDimensions.get(entry.getKey()))) {
                continue;
            }
            for (WallBlock block : entry.getValue()) {
                if (block.x() == x && block.y() == y && block.z() == z) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * 任务的全量方块（逐方块选中/高亮用），无记录返回空
     */
    public List<WallBlock> taskBlocks(String taskKey) {
        return blocksOf(taskKey);
    }

    private static Set<Long> chunksOf(List<WallBlock> placed) {
        Set<Long> result = new HashSet<>();
        for (WallBlock block : placed) {
            result.add(chunkLong(block.x() >> 4, block.z() >> 4));
        }
        return result;
    }

    private static long chunkLong(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private void rebuildChunks(String dimension, Set<Long> chunkKeys) {
        Map<Long, List<WallBlock>> grouped = new HashMap<>();
        for (Map.Entry<String, List<WallBlock>> entry : blocks.entrySet()) {
            if (!dimension.equals(blockDimensions.get(entry.getKey()))) {
                continue;
            }
            for (WallBlock block : entry.getValue()) {
                long key = chunkLong(block.x() >> 4, block.z() >> 4);
                if (chunkKeys.contains(key)) {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(block);
                }
            }
        }
        for (long key : chunkKeys) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            String rowKey = WallSchema.chunkKey(dimension, chunkX, chunkZ);
            List<WallBlock> cells = grouped.get(key);
            if (cells == null || cells.isEmpty()) {
                chunks.remove(rowKey);
                KingdomPersistence.delete(WallSchema.TABLE_CHUNK, WallSchema.COL_CHUNK_KEY, rowKey);
                continue;
            }
            chunks.put(rowKey, List.copyOf(cells));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(WallSchema.COL_CHUNK_KEY, rowKey);
            values.put(WallSchema.COL_DIMENSION, dimension);
            values.put(WallSchema.COL_CHUNK_X, chunkX);
            values.put(WallSchema.COL_CHUNK_Z, chunkZ);
            values.put(WallSchema.COL_CELLS, encodeCells(cells));
            values.put(WallSchema.COL_UPDATED_AT, System.currentTimeMillis());
            KingdomPersistence.submit(WallSchema.TABLE_CHUNK, WallSchema.COL_CHUNK_KEY, rowKey, values);
        }
    }

    private void load() {
        try {
            List<WallNode> nodeRows = KingdomPersistence.querySync(connection -> {
                List<WallNode> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + WallSchema.columnsNode()
                             + " FROM " + WallSchema.TABLE_NODE)) {
                    while (rs.next()) {
                        result.add(readNode(rs));
                    }
                }
                return result;
            });
            List<WallSegment> segmentRows = KingdomPersistence.querySync(connection -> {
                List<WallSegment> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + WallSchema.columnsSegment()
                             + " FROM " + WallSchema.TABLE_SEGMENT)) {
                    while (rs.next()) {
                        result.add(readSegment(rs));
                    }
                }
                return result;
            });
            KingdomPersistence.querySync(connection -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + WallSchema.COL_TASK_KEY + ", "
                             + WallSchema.COL_DIMENSION + ", " + WallSchema.COL_BLOCKS
                             + " FROM " + WallSchema.TABLE_BLOCKS)) {
                    while (rs.next()) {
                        String taskKey = rs.getString(WallSchema.COL_TASK_KEY);
                        blockDimensions.put(taskKey, rs.getString(WallSchema.COL_DIMENSION));
                        byte[] blob = rs.getBytes(WallSchema.COL_BLOCKS);
                        if (blob != null) {
                            blocks.put(taskKey, decodeCells(blob));
                        }
                    }
                }
                return null;
            });
            KingdomPersistence.querySync(connection -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + WallSchema.COL_CHUNK_KEY + ", "
                             + WallSchema.COL_CELLS + " FROM " + WallSchema.TABLE_CHUNK)) {
                    while (rs.next()) {
                        byte[] blob = rs.getBytes(WallSchema.COL_CELLS);
                        if (blob != null) {
                            chunks.put(rs.getString(WallSchema.COL_CHUNK_KEY), decodeCells(blob));
                        }
                    }
                }
                return null;
            });
            nodes.clear();
            for (WallNode node : nodeRows) {
                nodes.put(node.key(), node);
            }
            segments.clear();
            for (WallSegment segment : segmentRows) {
                segments.put(segment.key(), segment);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载城墙数据：{} 节点、{} 墙段、{} 份方块记录、{} 个区块聚合",
                    nodes.size(), segments.size(), blocks.size(), chunks.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载城墙数据失败", exception);
        }
    }

    private static Map<String, Object> nodeValues(WallNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WallSchema.COL_NODE_KEY, node.key());
        values.put(WallSchema.COL_DIMENSION, node.dimension());
        values.put(WallSchema.COL_X, node.pos().getX());
        values.put(WallSchema.COL_Y, node.pos().getY());
        values.put(WallSchema.COL_Z, node.pos().getZ());
        values.put(WallSchema.COL_KIND, node.kind().name());
        values.put(WallSchema.COL_ROTATION, node.rotation());
        values.put(WallSchema.COL_STATUS, node.status());
        values.put(WallSchema.COL_PROGRESS, node.progress());
        values.put(WallSchema.COL_TOTAL, node.total());
        values.put(WallSchema.COL_TERRITORY, node.territoryId());
        values.put(WallSchema.COL_OWNER, node.owner());
        values.put(WallSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static Map<String, Object> segmentValues(WallSegment segment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WallSchema.COL_SEGMENT_KEY, segment.key());
        values.put(WallSchema.COL_DIMENSION, segment.dimension());
        values.put(WallSchema.COL_AX, segment.a().getX());
        values.put(WallSchema.COL_AY, segment.a().getY());
        values.put(WallSchema.COL_AZ, segment.a().getZ());
        values.put(WallSchema.COL_BX, segment.b().getX());
        values.put(WallSchema.COL_BY, segment.b().getY());
        values.put(WallSchema.COL_BZ, segment.b().getZ());
        values.put(WallSchema.COL_STATUS, segment.status());
        values.put(WallSchema.COL_PROGRESS, segment.progress());
        values.put(WallSchema.COL_TOTAL, segment.total());
        values.put(WallSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static WallNode readNode(ResultSet rs) throws java.sql.SQLException {
        return new WallNode(
                rs.getString(WallSchema.COL_NODE_KEY),
                WallKind.parse(rs.getString(WallSchema.COL_KIND)),
                rs.getString(WallSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(WallSchema.COL_X), rs.getInt(WallSchema.COL_Y),
                        rs.getInt(WallSchema.COL_Z)),
                rs.getInt(WallSchema.COL_ROTATION),
                rs.getString(WallSchema.COL_STATUS),
                rs.getInt(WallSchema.COL_PROGRESS),
                rs.getInt(WallSchema.COL_TOTAL),
                rs.getString(WallSchema.COL_TERRITORY),
                rs.getString(WallSchema.COL_OWNER));
    }

    private static WallSegment readSegment(ResultSet rs) throws java.sql.SQLException {
        return new WallSegment(
                rs.getString(WallSchema.COL_SEGMENT_KEY),
                rs.getString(WallSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(WallSchema.COL_AX), rs.getInt(WallSchema.COL_AY),
                        rs.getInt(WallSchema.COL_AZ)),
                new BlockPos(rs.getInt(WallSchema.COL_BX), rs.getInt(WallSchema.COL_BY),
                        rs.getInt(WallSchema.COL_BZ)),
                rs.getString(WallSchema.COL_STATUS),
                rs.getInt(WallSchema.COL_PROGRESS),
                rs.getInt(WallSchema.COL_TOTAL));
    }

    private static byte[] encodeCells(List<WallBlock> cells) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(cells.size() * 16);
        for (WallBlock block : cells) {
            writeInt(out, block.x());
            writeInt(out, block.y());
            writeInt(out, block.z());
            writeInt(out, block.stateId());
        }
        return out.toByteArray();
    }

    private static List<WallBlock> decodeCells(byte[] blob) {
        if (blob == null || blob.length % 16 != 0) {
            return List.of();
        }
        List<WallBlock> result = new ArrayList<>(blob.length / 16);
        for (int offset = 0; offset + 16 <= blob.length; offset += 16) {
            result.add(new WallBlock(readInt(blob, offset), readInt(blob, offset + 4),
                    readInt(blob, offset + 8), readInt(blob, offset + 12)));
        }
        return result;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readInt(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24) | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
    }
}
