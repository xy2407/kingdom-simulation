package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 玩家档案：玩家名、首登状态、所属国家与领地、资金余额，按需建行并增量落库。
 */
public record PlayerProfile(UUID uuid, String playerName, String nationId, String territoryId,
                            boolean onboarded, int firstJoinDay, long money) {

    public PlayerProfile {
        playerName = playerName == null ? "" : playerName;
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
        money = Math.max(0L, money);
    }

    public static PlayerProfile fresh(UUID uuid, int day) {
        return new PlayerProfile(uuid, "", "", "", false, day, 0L);
    }

    public boolean hasNation() {
        return !nationId.isBlank();
    }

    public boolean hasTerritory() {
        return !territoryId.isBlank();
    }

    public PlayerProfile withName(String newPlayerName) {
        return new PlayerProfile(uuid, newPlayerName, nationId, territoryId, onboarded, firstJoinDay, money);
    }

    public PlayerProfile withNation(String newNationId) {
        return new PlayerProfile(uuid, playerName, newNationId, territoryId, onboarded, firstJoinDay, money);
    }

    public PlayerProfile withTerritory(String newTerritoryId) {
        return new PlayerProfile(uuid, playerName, nationId, newTerritoryId, onboarded, firstJoinDay, money);
    }

    public PlayerProfile withOnboarded() {
        return new PlayerProfile(uuid, playerName, nationId, territoryId, true, firstJoinDay, money);
    }

    public PlayerProfile withMoney(long newMoney) {
        return new PlayerProfile(uuid, playerName, nationId, territoryId, onboarded, firstJoinDay, newMoney);
    }

    public PlayerProfile withMoneyDelta(long delta) {
        return withMoney(money + delta);
    }
}
