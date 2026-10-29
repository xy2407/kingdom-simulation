package com.xy2407.kingdom.server.faction;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.MoneyLedgerSchema;

import net.minecraft.server.MinecraftServer;

/**
 * 资金流水内存表：每块领地各自一条队列，保留最近若干笔并落库
 */
public final class MoneyLedgerStore {

    public static final MoneyLedgerStore INSTANCE = new MoneyLedgerStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private static final int KEEP_PER_TERRITORY = 60;

    private final Map<String, Deque<Entry>> byTerritory = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private MoneyLedgerStore() {
    }

    public record Entry(String kind, long amount, long balanceAfter, long stamp) {

        public Entry {
            kind = kind == null ? "" : kind;
        }
    }

    public void reset() {
        byTerritory.clear();
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

    public List<Entry> recent(String territoryId) {
        if (territoryId == null) {
            return List.of();
        }
        Deque<Entry> queue = byTerritory.get(territoryId);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    public void append(String territoryId, String kind, long amount, long balanceAfter) {
        if (territoryId == null || amount == 0L) {
            return;
        }
        Entry entry = new Entry(kind, amount, balanceAfter, System.currentTimeMillis());
        Deque<Entry> queue = byTerritory.computeIfAbsent(territoryId, key -> new ArrayDeque<>());
        queue.addFirst(entry);
        while (queue.size() > KEEP_PER_TERRITORY) {
            queue.removeLast();
        }
        String entryId = UUID.randomUUID().toString();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(MoneyLedgerSchema.COL_ENTRY_ID, entryId);
        values.put(MoneyLedgerSchema.COL_TERRITORY_ID, territoryId);
        values.put(MoneyLedgerSchema.COL_KIND, entry.kind());
        values.put(MoneyLedgerSchema.COL_AMOUNT, entry.amount());
        values.put(MoneyLedgerSchema.COL_BALANCE_AFTER, entry.balanceAfter());
        values.put(MoneyLedgerSchema.COL_STAMP, entry.stamp());
        KingdomPersistence.submit(MoneyLedgerSchema.TABLE_LEDGER,
                MoneyLedgerSchema.COL_ENTRY_ID, entryId, values);
    }

    private void load() {
        try {
            Map<String, Deque<Entry>> rows = KingdomPersistence.querySync(connection -> {
                Map<String, Deque<Entry>> result = new HashMap<>();
                String sql = "SELECT " + MoneyLedgerSchema.columns() + " FROM "
                        + MoneyLedgerSchema.TABLE_LEDGER + " ORDER BY "
                        + MoneyLedgerSchema.COL_STAMP + " ASC";
                try (PreparedStatement ps = connection.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String territoryId = rs.getString(MoneyLedgerSchema.COL_TERRITORY_ID);
                        if (territoryId == null || territoryId.isBlank()) {
                            continue;
                        }
                        Entry entry = new Entry(
                                rs.getString(MoneyLedgerSchema.COL_KIND),
                                rs.getLong(MoneyLedgerSchema.COL_AMOUNT),
                                rs.getLong(MoneyLedgerSchema.COL_BALANCE_AFTER),
                                rs.getLong(MoneyLedgerSchema.COL_STAMP));
                        Deque<Entry> queue = result.computeIfAbsent(territoryId, key -> new ArrayDeque<>());
                        queue.addFirst(entry);
                        while (queue.size() > KEEP_PER_TERRITORY) {
                            queue.removeLast();
                        }
                    }
                }
                return result;
            });
            byTerritory.clear();
            byTerritory.putAll(rows);
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 块领地的资金流水", rows.size());
        } catch (SQLException | RuntimeException exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载资金流水失败", exception);
        }
    }
}
