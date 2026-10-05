package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.TerritorySchema;

import net.minecraft.core.BlockPos;

/**
 * 领地登记表。
 *
 * <p><b>范围</b>：以市政厅方块所在区块为中心、向四个方向各扩 {@link Territory#CHUNK_RADIUS}
 * 格，即 5×5 共 {@link Territory#CHUNK_COUNT} 个水平区块。创建时一次算好四个角点存进去，
 * 之后判定归属只做整数比较。</p>
 *
 * <p><b>一个玩家一块</b>：{@link #byOwner} 命中时 {@link #create} 直接拒绝，
 * 不去覆盖已有的那块——覆盖等于无声抹掉玩家之前的领地。</p>
 */
public final class TerritoryRegistry {

    public static final TerritoryRegistry INSTANCE = new TerritoryRegistry();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final String ID_PREFIX = "territory-";

    private final Map<String, Territory> byId = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private TerritoryRegistry() {
    }

    public void reset() {
        byId.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    /**
     * 表是否已经从库里读出来。
     *
     * <p>右键市政厅时要靠它分流。没读完就 {@link #byOwner} 一律返回 null，
     * 会被当成"这人还没有领地"，于是刚进服的玩家一右键就被引到建城界面——
     * 哪怕他早就建过。所以分流前必须先问这一句。</p>
     */
    public boolean isLoaded() {
        return loaded;
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

    private void load() {
        try {
            int[] skipped = {0};
            List<Territory> rows = KingdomPersistence.querySync(connection -> {
                List<Territory> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + TerritorySchema.columns()
                             + " FROM " + TerritorySchema.TABLE_TERRITORY)) {
                    while (rs.next()) {
                        /* 单行坏掉不该让整表加载失败。历史上写入端漏写主键
                           （见 Database.executeOp 的说明），留下过 id 为 NULL 的废行；
                           它读出来是 null，put 进 ConcurrentHashMap 会抛 NPE，
                           一抛整张领地表就永远读不出来——而"领地表没读完"正是
                           市政厅被判成"你没有领地"、界面打不开的直接原因。
                           Database 启动时会清这些行，这里再兜一层。 */
                        try {
                            Territory territory = readRow(rs);
                            if (territory.id() == null || territory.id().isBlank()) {
                                skipped[0]++;
                                continue;
                            }
                            result.add(territory);
                        } catch (RuntimeException brokenRow) {
                            skipped[0]++;
                        }
                    }
                }
                return result;
            });
            byId.clear();
            for (Territory territory : rows) {
                byId.put(territory.id(), territory);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 块领地{}", byId.size(),
                    skipped[0] > 0 ? "（跳过 " + skipped[0] + " 行废数据）" : "");
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载领地数据失败", exception);
        }
    }

    public List<Territory> all() {
        return List.copyOf(byId.values());
    }

    public Territory byId(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 某个玩家已有的领地；没有则 null。一人一块，所以最多命中一条。 */
    public Territory byOwner(UUID owner) {
        if (owner == null) {
            return null;
        }
        for (Territory territory : byId.values()) {
            if (owner.equals(territory.owner())) {
                return territory;
            }
        }
        return null;
    }

    /** 某水平区块归哪块领地；不属于任何领地时返回 null。 */
    public Territory byChunk(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return null;
        }
        for (Territory territory : byId.values()) {
            if (dimension.equals(territory.dimension()) && territory.containsChunk(chunkX, chunkZ)) {
                return territory;
            }
        }
        return null;
    }

    /** 世界坐标某点归哪块领地。 */
    public Territory byBlock(String dimension, double x, double z) {
        return byChunk(dimension,
                (int) Math.floor(x / 16.0),
                (int) Math.floor(z / 16.0));
    }

    /**
     * 以市政厅方块为中心建一块领地。
     *
     * <p>范围固定 5×5 区块：中心区块 ±{@link Territory#CHUNK_RADIUS}。
     * 越界不管——负坐标区块本来就是合法编号，MC 的世界边界远在更外面。</p>
     *
     * @return 新建的领地；该玩家已有领地时返回 {@code null}（不覆盖）
     */
    public Territory create(UUID owner, String nationId, String name,
                            String dimension, BlockPos center, int day) {
        if (owner == null || byOwner(owner) != null) {
            return null;
        }
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int radius = Territory.CHUNK_RADIUS;

        Territory territory = new Territory(
                nextId(), name, nationId == null ? "" : nationId, owner, dimension,
                center.getX(), center.getZ(),
                centerChunkX - radius, centerChunkZ - radius,
                centerChunkX + radius, centerChunkZ + radius,
                day);
        byId.put(territory.id(), territory);
        KingdomPersistence.submit(TerritorySchema.TABLE_TERRITORY, TerritorySchema.COL_ID,
                territory.id(), toValues(territory));
        return territory;
    }

    private String nextId() {
        int max = 0;
        for (String id : byId.keySet()) {
            if (id.startsWith(ID_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(ID_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    /* 手工改过的脏 id，跳过 */
                }
            }
        }
        return ID_PREFIX + (max + 1);
    }

    private static Territory readRow(ResultSet rs) throws SQLException {
        String owner = rs.getString(TerritorySchema.COL_OWNER_UUID);
        UUID ownerId;
        try {
            ownerId = owner == null || owner.isBlank() ? null : UUID.fromString(owner);
        } catch (IllegalArgumentException exception) {
            ownerId = null;
        }
        return new Territory(
                rs.getString(TerritorySchema.COL_ID),
                rs.getString(TerritorySchema.COL_NAME),
                rs.getString(TerritorySchema.COL_NATION_ID),
                ownerId,
                rs.getString(TerritorySchema.COL_DIMENSION),
                rs.getInt(TerritorySchema.COL_CENTER_X),
                rs.getInt(TerritorySchema.COL_CENTER_Z),
                rs.getInt(TerritorySchema.COL_MIN_CHUNK_X),
                rs.getInt(TerritorySchema.COL_MIN_CHUNK_Z),
                rs.getInt(TerritorySchema.COL_MAX_CHUNK_X),
                rs.getInt(TerritorySchema.COL_MAX_CHUNK_Z),
                rs.getInt(TerritorySchema.COL_CREATED_DAY));
    }

    private static Map<String, Object> toValues(Territory territory) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TerritorySchema.COL_NAME, territory.name());
        values.put(TerritorySchema.COL_NATION_ID, territory.nationId());
        values.put(TerritorySchema.COL_OWNER_UUID,
                territory.owner() == null ? "" : territory.owner().toString());
        values.put(TerritorySchema.COL_DIMENSION, territory.dimension());
        values.put(TerritorySchema.COL_CENTER_X, territory.centerX());
        values.put(TerritorySchema.COL_CENTER_Z, territory.centerZ());
        values.put(TerritorySchema.COL_MIN_CHUNK_X, territory.minChunkX());
        values.put(TerritorySchema.COL_MIN_CHUNK_Z, territory.minChunkZ());
        values.put(TerritorySchema.COL_MAX_CHUNK_X, territory.maxChunkX());
        values.put(TerritorySchema.COL_MAX_CHUNK_Z, territory.maxChunkZ());
        values.put(TerritorySchema.COL_CREATED_DAY, territory.createdDay());
        values.put(TerritorySchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
