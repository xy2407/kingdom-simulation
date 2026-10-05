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
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.PlayerProfileSchema;

/**
 * 玩家档案表：首登标记与归属。
 *
 * <p>"是否初次登录"就落在 {@code onboarded} 这一列上——玩家登录时若查到 false，
 * 服务端就发一个包让客户端弹阵营选择界面；选完置 true，之后不再弹。</p>
 *
 * <p>档案按需建行：玩家第一次被问到时才写库，不预铺所有玩家。</p>
 */
public final class PlayerProfileStore {

    public static final PlayerProfileStore INSTANCE = new PlayerProfileStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<UUID, PlayerProfile> byId = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;

    private PlayerProfileStore() {
    }

    public void reset() {
        byId.clear();
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

    private void load() {
        try {
            int[] skipped = {0};
            List<PlayerProfile> rows = KingdomPersistence.querySync(connection -> {
                List<PlayerProfile> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + PlayerProfileSchema.columns()
                             + " FROM " + PlayerProfileSchema.TABLE_PLAYER_PROFILE)) {
                    while (rs.next()) {
                        /* 单行坏掉不该让整表加载失败。历史上写入端漏写主键
                           （见 Database.executeOp 的说明），留下过 uuid 为 NULL 的废行；
                           UUID.fromString(null) 直接抛 NPE，一抛整份档案表就永远读不出来
                           （每 tick 重试、每 tick 报一条 ERROR），
                           连带"有没有领地"判定失效、市政厅界面打不开。
                           Database 启动时会清这些行，这里再兜一层。 */
                        try {
                            PlayerProfile profile = readRow(rs);
                            if (profile.uuid() == null) {
                                skipped[0]++;
                                continue;
                            }
                            result.add(profile);
                        } catch (RuntimeException brokenRow) {
                            skipped[0]++;
                        }
                    }
                }
                return result;
            });
            byId.clear();
            for (PlayerProfile profile : rows) {
                byId.put(profile.uuid(), profile);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 份玩家档案{}", byId.size(),
                    skipped[0] > 0 ? "（跳过 " + skipped[0] + " 行废数据）" : "");
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载玩家档案失败", exception);
        }
    }

    /**
     * 全表是否已从库里读出来。
     *
     * <p>首登驱动必须先问这一句：没读完就 {@code remember}，会给老玩家误建一份
     * "全新档案"，把他的国家抹掉。</p>
     */
    public boolean isLoaded() {
        return loaded;
    }

    /** 查档案；没有则返回 null（不隐式建行，免得把"查一下"变成"写一次"）。 */
    public PlayerProfile byUuid(UUID uuid) {
        return uuid == null ? null : byId.get(uuid);
    }

    /**
     * 取档案，没有就建一份"全新玩家"的并落库。
     *
     * <p>玩家登录时调它——这样"是不是初次登录"这件事一定有答案。</p>
     */
    public PlayerProfile remember(UUID uuid, int day) {
        if (uuid == null) {
            return null;
        }
        PlayerProfile existing = byId.get(uuid);
        if (existing != null) {
            return existing;
        }
        PlayerProfile profile = PlayerProfile.fresh(uuid, day);
        byId.put(uuid, profile);
        persist(profile);
        return profile;
    }

    /** 走完首登流程：记下选定的国家并置 onboarded。 */
    public PlayerProfile markOnboarded(UUID uuid, String nationId) {
        PlayerProfile profile = byId.get(uuid);
        if (profile == null) {
            return null;
        }
        PlayerProfile updated = profile.withNation(nationId).withOnboarded();
        byId.put(uuid, updated);
        persist(updated);
        return updated;
    }

    /**
     * 记下这名玩家看地图时停留的中心点。
     *
     * <p>由 {@code WorldMapServer} 在收到采样请求时调用——请求里带的中心就是
     * 玩家正在看的地方。每人一份，互不影响。</p>
     */
    public PlayerProfile setMapCenter(UUID uuid, int x, int z) {
        PlayerProfile profile = byId.get(uuid);
        if (profile == null) {
            return null;
        }
        if (profile.mapCenterX() == x && profile.mapCenterZ() == z) {
            /* 没变就不写库：拖动地图时请求会比较密，省掉无谓的落盘 */
            return profile;
        }
        PlayerProfile updated = profile.withMapCenter(x, z);
        byId.put(uuid, updated);
        persist(updated);
        return updated;
    }

    /** 建好领地后回填归属。 */
    public PlayerProfile setTerritory(UUID uuid, String territoryId) {
        PlayerProfile profile = byId.get(uuid);
        if (profile == null) {
            return null;
        }
        PlayerProfile updated = profile.withTerritory(territoryId);
        byId.put(uuid, updated);
        persist(updated);
        return updated;
    }

    private static void persist(PlayerProfile profile) {
        KingdomPersistence.submit(PlayerProfileSchema.TABLE_PLAYER_PROFILE,
                PlayerProfileSchema.COL_UUID, profile.uuid().toString(), toValues(profile));
    }

    private static PlayerProfile readRow(ResultSet rs) throws SQLException {
        return new PlayerProfile(
                UUID.fromString(rs.getString(PlayerProfileSchema.COL_UUID)),
                rs.getString(PlayerProfileSchema.COL_NATION_ID),
                rs.getString(PlayerProfileSchema.COL_TERRITORY_ID),
                rs.getInt(PlayerProfileSchema.COL_ONBOARDED) != 0,
                rs.getInt(PlayerProfileSchema.COL_FIRST_JOIN_DAY),
                rs.getInt(PlayerProfileSchema.COL_MAP_CENTER_X),
                rs.getInt(PlayerProfileSchema.COL_MAP_CENTER_Z));
    }

    private static Map<String, Object> toValues(PlayerProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PlayerProfileSchema.COL_NATION_ID, profile.nationId());
        values.put(PlayerProfileSchema.COL_TERRITORY_ID, profile.territoryId());
        values.put(PlayerProfileSchema.COL_ONBOARDED, profile.onboarded() ? 1 : 0);
        values.put(PlayerProfileSchema.COL_FIRST_JOIN_DAY, profile.firstJoinDay());
        values.put(PlayerProfileSchema.COL_MAP_CENTER_X, profile.mapCenterX());
        values.put(PlayerProfileSchema.COL_MAP_CENTER_Z, profile.mapCenterZ());
        values.put(PlayerProfileSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
