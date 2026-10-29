package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.PlayerGrantSchema;

import net.minecraft.server.MinecraftServer;

/**
 * 启动资金发放记录：按玩家 UUID 记忆是否已领，保证只发一次
 */
public final class PlayerGrantStore {

    public static final PlayerGrantStore INSTANCE = new PlayerGrantStore();

    public static final long STARTER_AMOUNT = 50L;

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Set<UUID> granted = new HashSet<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private PlayerGrantStore() {
    }

    public void reset() {
        granted.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public void tick(MinecraftServer server) {
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

    public boolean isGranted(UUID uuid) {
        return uuid != null && granted.contains(uuid);
    }

    public void mark(UUID uuid, long amount, int day) {
        if (uuid == null) {
            return;
        }
        granted.add(uuid);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PlayerGrantSchema.COL_UUID, uuid.toString());
        values.put(PlayerGrantSchema.COL_AMOUNT, amount);
        values.put(PlayerGrantSchema.COL_GRANTED_DAY, day);
        values.put(PlayerGrantSchema.COL_GRANTED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(PlayerGrantSchema.TABLE_GRANT,
                PlayerGrantSchema.COL_UUID, uuid.toString(), values);
    }

    private void load() {
        try {
            List<UUID> rows = KingdomPersistence.querySync(connection -> {
                List<UUID> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + PlayerGrantSchema.COL_UUID
                             + " FROM " + PlayerGrantSchema.TABLE_GRANT)) {
                    while (rs.next()) {
                        String raw = rs.getString(PlayerGrantSchema.COL_UUID);
                        if (raw == null || raw.isBlank()) {
                            continue;
                        }
                        try {
                            result.add(UUID.fromString(raw));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                return result;
            });
            granted.clear();
            granted.addAll(rows);
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 条启动资金记录", rows.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载启动资金记录失败", exception);
        }
    }
}
