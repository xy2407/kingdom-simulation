package com.xy2407.kingdom.server.faction;

import java.util.UUID;

/**
 * 资金唯一出入口：全场只有玩家余额一个数据源，领地与国家花费一律扣玩家余额。
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

    // TODO 待扩展（全部走 credit/debit，不另设资金维度）：
    //  税收征收 / 交易与商店 / 工资俸禄 / 军费支出 / 外交进贡 / 政务奖惩
    //  资金流水（chLedgerList）暂留空，接入时在此记账并向 CityHallDataPacket.ledger 填充
}
