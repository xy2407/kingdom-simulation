package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NationSchema;

/**
 * 国家登记表：只管**玩家自建**的那些国家。
 *
 * <p>12 个预置阵营由客户端页面（{@code ui/start.html}）硬编码，服务端不落库——
 * 两边各存一份迟早会对不上，所以这里只留一个 id 白名单用于校验"选的是不是合法阵营"。</p>
 *
 * <p><b>自建国永久保留</b>：本类不提供任何删除方法，这是需求里定死的约束。
 * 以后真要放开删除，得先把"其它玩家可能已经选了它"这件事想清楚再说，
 * 别顺手加一个 remove 上去。</p>
 */
public final class NationRegistry {

    public static final NationRegistry INSTANCE = new NationRegistry();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    /** 自建国 id 的前缀，后面接自增号：realm-1、realm-2…… */
    public static final String PLAYER_ID_PREFIX = "realm-";

    /** 客户端 start.html 里那 12 个预置阵营的 id，选国时用来校验。 */
    private static final Set<String> BUILTIN_IDS = Set.of(
            "valarian", "bohemian", "caladrian", "numerian", "visgothian", "galician",
            "lancastian", "hospitaller", "templar", "barathian", "orleanian", "burgundian");

    private final Map<String, Nation> byId = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private NationRegistry() {
    }

    public void reset() {
        byId.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    /** 懒加载：数据库就绪后读一次；失败退避重试，不阻塞主线程。 */
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
            List<Nation> rows = KingdomPersistence.querySync(connection -> {
                List<Nation> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + NationSchema.columns()
                             + " FROM " + NationSchema.TABLE_NATION)) {
                    while (rs.next()) {
                        /* 单行坏掉不该让整表加载失败。历史上写入端漏写主键
                           （见 Database.executeOp 的说明），留下过主键为 NULL 的废行；
                           读出来 id 是 null，put 进 ConcurrentHashMap 会抛 NPE，
                           一抛就整张表永远读不出来、每 tick 重试报错。
                           Database 启动时已经会清这些行，这里再兜一层。 */
                        try {
                            Nation nation = readRow(rs);
                            if (nation.id() == null || nation.id().isBlank()) {
                                skipped[0]++;
                                continue;
                            }
                            result.add(nation);
                        } catch (RuntimeException brokenRow) {
                            skipped[0]++;
                        }
                    }
                }
                return result;
            });
            byId.clear();
            for (Nation nation : rows) {
                byId.put(nation.id(), nation);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 个玩家自建国家{}", byId.size(),
                    skipped[0] > 0 ? "（跳过 " + skipped[0] + " 行废数据）" : "");
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载国家数据失败", exception);
        }
    }

    /**
     * 玩家自建的国家，新玩家首登时能选到它们。
     *
     * <p>按建国顺序返回，列表里先建的排前面。</p>
     */
    public List<Nation> playerNations() {
        return List.copyOf(byId.values());
    }

    public Nation byId(String id) {
        return id == null ? null : byId.get(id);
    }

    /** id 是否是个合法阵营：命中预置集合，或表里已有这个自建国。 */
    public boolean isKnown(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return BUILTIN_IDS.contains(id) || byId.containsKey(id);
    }

    /** 是否是预置阵营（不是玩家建的）。 */
    public static boolean isBuiltin(String id) {
        return id != null && BUILTIN_IDS.contains(id);
    }

    /**
     * 建一个玩家国家。
     *
     * <p>id 自增（{@code realm-N}，N 取现有最大值 +1），{@code permanent} 恒为 true——
     * 这个国家从此在该存档永久存在。</p>
     *
     * @return 新国家；数据库未就绪时也照建（内存生效，落库会随队列补上）
     */
    public Nation create(String name, String ruler, String motto,
                         String colorLeft, String colorRight,
                         String emblem, String emblemColor, UUID founder, int day) {
        Nation nation = new Nation(nextId(), name, ruler, motto, colorLeft, colorRight,
                emblem, emblemColor, founder == null ? "" : founder.toString(), true, day);
        byId.put(nation.id(), nation);
        KingdomPersistence.submit(NationSchema.TABLE_NATION, NationSchema.COL_ID,
                nation.id(), toValues(nation));
        return nation;
    }

    /** 下一个可用的自建国 id。编号只增不减，删过的号也不复用。 */
    private String nextId() {
        int max = 0;
        for (String id : byId.keySet()) {
            if (id.startsWith(PLAYER_ID_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(PLAYER_ID_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    /* 手工改过的脏 id，跳过即可 */
                }
            }
        }
        return PLAYER_ID_PREFIX + (max + 1);
    }

    private static Nation readRow(ResultSet rs) throws SQLException {
        return new Nation(
                rs.getString(NationSchema.COL_ID),
                rs.getString(NationSchema.COL_NAME),
                rs.getString(NationSchema.COL_RULER),
                rs.getString(NationSchema.COL_MOTTO),
                rs.getString(NationSchema.COL_COLOR_LEFT),
                rs.getString(NationSchema.COL_COLOR_RIGHT),
                rs.getString(NationSchema.COL_EMBLEM),
                rs.getString(NationSchema.COL_EMBLEM_COLOR),
                rs.getString(NationSchema.COL_FOUNDER_UUID),
                rs.getInt(NationSchema.COL_PERMANENT) != 0,
                rs.getInt(NationSchema.COL_CREATED_DAY));
    }

    private static Map<String, Object> toValues(Nation nation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(NationSchema.COL_NAME, nation.name());
        values.put(NationSchema.COL_RULER, nation.ruler());
        values.put(NationSchema.COL_MOTTO, nation.motto());
        values.put(NationSchema.COL_COLOR_LEFT, nation.colorLeft());
        values.put(NationSchema.COL_COLOR_RIGHT, nation.colorRight());
        values.put(NationSchema.COL_EMBLEM, nation.emblem());
        values.put(NationSchema.COL_EMBLEM_COLOR, nation.emblemColor());
        values.put(NationSchema.COL_FOUNDER_UUID, nation.founderUuid());
        values.put(NationSchema.COL_PERMANENT, nation.permanent() ? 1 : 0);
        values.put(NationSchema.COL_CREATED_DAY, nation.createdDay());
        values.put(NationSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
