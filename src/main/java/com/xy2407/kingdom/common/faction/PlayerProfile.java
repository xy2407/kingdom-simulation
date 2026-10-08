package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 玩家档案：首登状态、所属国家与领地，以及这名玩家上次查看地图时停留的中心点。
 */
public record PlayerProfile(UUID uuid, String nationId, String territoryId,
                            boolean onboarded, int firstJoinDay,
                            int mapCenterX, int mapCenterZ) {

    public PlayerProfile {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
    }

    public static PlayerProfile fresh(UUID uuid, int day) {
        return new PlayerProfile(uuid, "", "", false, day, 0, 0);
    }

    public boolean hasNation() {
        return !nationId.isBlank();
    }

    public boolean hasTerritory() {
        return !territoryId.isBlank();
    }

    public boolean hasMapCenter() {
        return mapCenterX != 0 || mapCenterZ != 0;
    }

    public PlayerProfile withNation(String newNationId) {
        return new PlayerProfile(uuid, newNationId, territoryId, onboarded, firstJoinDay,
                mapCenterX, mapCenterZ);
    }

    public PlayerProfile withTerritory(String newTerritoryId) {
        return new PlayerProfile(uuid, nationId, newTerritoryId, onboarded, firstJoinDay,
                mapCenterX, mapCenterZ);
    }

    public PlayerProfile withOnboarded() {
        return new PlayerProfile(uuid, nationId, territoryId, true, firstJoinDay,
                mapCenterX, mapCenterZ);
    }

    public PlayerProfile withMapCenter(int x, int z) {
        return new PlayerProfile(uuid, nationId, territoryId, onboarded, firstJoinDay, x, z);
    }
}
