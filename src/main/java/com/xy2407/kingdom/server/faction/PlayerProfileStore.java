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
 * 玩家档案表：首登标记与归属；档案按需建行，不预铺所有玩家。
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

    public boolean isLoaded() {
        return loaded;
    }

    public PlayerProfile byUuid(UUID uuid) {
        return uuid == null ? null : byId.get(uuid);
    }

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
                rs.getInt(PlayerProfileSchema.COL_FIRST_JOIN_DAY));
    }

    private static Map<String, Object> toValues(PlayerProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PlayerProfileSchema.COL_NATION_ID, profile.nationId());
        values.put(PlayerProfileSchema.COL_TERRITORY_ID, profile.territoryId());
        values.put(PlayerProfileSchema.COL_ONBOARDED, profile.onboarded() ? 1 : 0);
        values.put(PlayerProfileSchema.COL_FIRST_JOIN_DAY, profile.firstJoinDay());
        values.put(PlayerProfileSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }
}
