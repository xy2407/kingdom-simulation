package com.xy2407.kingdom.server.faction;

import java.util.UUID;

/**
 * 资金出入口：玩家钱包、领地金库、市民私产三本账，存取与收益统一记流水
 */
public final class MoneyService {

    public record Result(boolean success, long balance) {
    }

    private MoneyService() {
    }

    public static long balance(UUID uuid) {
        return PlayerProfileStore.INSTANCE.money(uuid);
    }

    public static Result credit(UUID uuid, long amount) {
        if (amount < 0) {
            return new Result(false, balance(uuid));
        }
        PlayerProfileStore.INSTANCE.addMoney(uuid, amount);
        return new Result(true, balance(uuid));
    }

    public static Result debit(UUID uuid, long amount) {
        if (amount < 0) {
            return new Result(false, balance(uuid));
        }
        return PlayerProfileStore.INSTANCE.trySpend(uuid, amount)
                ? new Result(true, balance(uuid))
                : new Result(false, balance(uuid));
    }

    public static Result set(UUID uuid, long amount) {
        if (amount < 0) {
            return new Result(false, balance(uuid));
        }
        PlayerProfileStore.INSTANCE.addMoney(uuid, amount - balance(uuid));
        return new Result(true, balance(uuid));
    }

    public static long territoryBalance(String territoryId) {
        return TerritoryTreasuryStore.INSTANCE.balance(territoryId);
    }

    public static long creditTerritory(String territoryId, long amount, String kind) {
        if (territoryId == null || amount <= 0L) {
            return territoryBalance(territoryId);
        }
        long after = TerritoryTreasuryStore.INSTANCE.deposit(territoryId, amount);
        MoneyLedgerStore.INSTANCE.append(territoryId, kind, amount, after);
        return after;
    }

    public static boolean debitTerritory(String territoryId, long amount, String kind) {
        if (territoryId == null || amount <= 0L) {
            return false;
        }
        if (!TerritoryTreasuryStore.INSTANCE.withdraw(territoryId, amount)) {
            return false;
        }
        MoneyLedgerStore.INSTANCE.append(territoryId, kind, -amount,
                TerritoryTreasuryStore.INSTANCE.balance(territoryId));
        return true;
    }

    public static Result depositToTerritory(UUID uuid, String territoryId, long amount) {
        if (uuid == null || territoryId == null || amount <= 0L) {
            return new Result(false, balance(uuid));
        }
        if (!PlayerProfileStore.INSTANCE.trySpend(uuid, amount)) {
            return new Result(false, balance(uuid));
        }
        long after = TerritoryTreasuryStore.INSTANCE.deposit(territoryId, amount);
        MoneyLedgerStore.INSTANCE.append(territoryId, "玩家存入", amount, after);
        return new Result(true, balance(uuid));
    }

    public static Result withdrawFromTerritory(UUID uuid, String territoryId, long amount) {
        if (uuid == null || territoryId == null || amount <= 0L) {
            return new Result(false, balance(uuid));
        }
        if (!TerritoryTreasuryStore.INSTANCE.withdraw(territoryId, amount)) {
            return new Result(false, balance(uuid));
        }
        PlayerProfileStore.INSTANCE.addMoney(uuid, amount);
        MoneyLedgerStore.INSTANCE.append(territoryId, "玩家提取", -amount,
                TerritoryTreasuryStore.INSTANCE.balance(territoryId));
        return new Result(true, balance(uuid));
    }
}
