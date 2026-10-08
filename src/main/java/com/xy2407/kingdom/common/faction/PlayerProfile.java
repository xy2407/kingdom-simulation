package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 玩家档案：首登状态、所属国家与领地，按需建行并增量落库。
 */
public record PlayerProfile(UUID uuid, String nationId, String territoryId,
                            boolean onboarded, int firstJoinDay) {

    public PlayerProfile {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
    }

    public static PlayerProfile fresh(UUID uuid, int day) {
        return new PlayerProfile(uuid, "", "", false, day);
    }

    public boolean hasNation() {
        return !nationId.isBlank();
    }

    public boolean hasTerritory() {
        return !territoryId.isBlank();
    }

    public PlayerProfile withNation(String newNationId) {
        return new PlayerProfile(uuid, newNationId, territoryId, onboarded, firstJoinDay);
    }

    public PlayerProfile withTerritory(String newTerritoryId) {
        return new PlayerProfile(uuid, nationId, newTerritoryId, onboarded, firstJoinDay);
    }

    public PlayerProfile withOnboarded() {
        return new PlayerProfile(uuid, nationId, territoryId, true, firstJoinDay);
    }
}
