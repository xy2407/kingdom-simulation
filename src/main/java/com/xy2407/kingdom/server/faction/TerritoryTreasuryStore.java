package com.xy2407.kingdom.server.faction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.TerritoryTreasurySchema;

import net.minecraft.server.MinecraftServer;

/**
 * 领地金库内存表：一块领地一个余额，无上限，变动即落库
 */
public final class TerritoryTreasuryStore {

    public static final TerritoryTreasuryStore INSTANCE = new TerritoryTreasuryStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<String, Long> balances = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private TerritoryTreasuryStore() {
    }

    public void reset() {
        balances.clear();
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

    public long balance(String territoryId) {
        if (territoryId == null) {
            return 0L;
        }
        Long value = balances.get(territoryId);
        return value == null ? 0L : value;
    }

    public long deposit(String territoryId, long amount) {
        if (territoryId == null || amount <= 0L) {
            return balance(territoryId);
        }
        return write(territoryId, balance(territoryId) + amount);
    }

    public boolean withdraw(String territoryId, long amount) {
        if (territoryId == null || amount <= 0L) {
            return false;
        }
        long current = balance(territoryId);
        if (current < amount) {
            return false;
        }
        write(territoryId, current - amount);
        return true;
    }

    public long set(String territoryId, long amount) {
        if (territoryId == null) {
            return 0L;
        }
        return write(territoryId, Math.max(0L, amount));
    }

    private long write(String territoryId, long amount) {
        long value = Math.max(0L, amount);
        balances.put(territoryId, value);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TerritoryTreasurySchema.COL_TERRITORY_ID, territoryId);
        values.put(TerritoryTreasurySchema.COL_BALANCE, value);
        values.put(TerritoryTreasurySchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(TerritoryTreasurySchema.TABLE_TREASURY,
                TerritoryTreasurySchema.COL_TERRITORY_ID, territoryId, values);
        return value;
    }

    private void load() {
        try {
            List<Map.Entry<String, Long>> rows = KingdomPersistence.querySync(connection -> {
                List<Map.Entry<String, Long>> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + TerritoryTreasurySchema.columns()
                             + " FROM " + TerritoryTreasurySchema.TABLE_TREASURY)) {
                    while (rs.next()) {
                        String id = rs.getString(TerritoryTreasurySchema.COL_TERRITORY_ID);
                        if (id == null || id.isBlank()) {
                            continue;
                        }
                        result.add(Map.entry(id, Math.max(0L, rs.getLong(TerritoryTreasurySchema.COL_BALANCE))));
                    }
                }
                return result;
            });
            balances.clear();
            for (Map.Entry<String, Long> row : rows) {
                balances.put(row.getKey(), row.getValue());
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 块领地金库", rows.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载领地金库失败", exception);
        }
    }
}
