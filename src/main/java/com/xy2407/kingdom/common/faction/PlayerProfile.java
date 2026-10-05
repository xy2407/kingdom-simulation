package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 玩家档案：首登状态、归属，以及上次查看地图的位置。
 *
 * <p>它管三件事：</p>
 * <ol>
 *   <li>{@code onboarded=false} 表示这名玩家还没走完选国流程——登录时据此弹界面；</li>
 *   <li>{@code nationId} 是首登时选定的国家，{@code territoryId} 是自己那块领地；</li>
 *   <li>{@code mapCenterX/Z} 是<b>这名玩家自己</b>上次看地图时停留的中心点。
 *       每人一份，互不影响——他拖动地图后松手，服务端就把新中心记在这儿，
 *       下次打开界面从他上次看到的地方接着看，而不是每次跳回领地中心。</li>
 * </ol>
 *
 * <p>以玩家 UUID 为主键，与原版 playerdata 无关，改名换皮肤都不受影响。</p>
 *
 * @param uuid         玩家 UUID
 * @param nationId     所属国家 id，空串表示未选
 * @param territoryId  自己的领地 id，空串表示未建
 * @param onboarded    是否已走完首登流程
 * @param firstJoinDay 首次进入时的世界天数
 * @param mapCenterX   上次查看地图的中心 X（0 表示还没记过）
 * @param mapCenterZ   上次查看地图的中心 Z
 */
public record PlayerProfile(UUID uuid, String nationId, String territoryId,
                            boolean onboarded, int firstJoinDay,
                            int mapCenterX, int mapCenterZ) {

    public PlayerProfile {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
    }

    /** 新玩家第一次进存档时的初始档案：什么都没选，也没走完流程。 */
    public static PlayerProfile fresh(UUID uuid, int day) {
        return new PlayerProfile(uuid, "", "", false, day, 0, 0);
    }

    public boolean hasNation() {
        return !nationId.isBlank();
    }

    public boolean hasTerritory() {
        return !territoryId.isBlank();
    }

    /**
     * 有没有记过地图中心。
     *
     * <p>(0, 0) 当作"没记过"——世界原点是海，不会有人恰好把地图中心停在那儿，
     * 为它单开一个 boolean 列不值得。</p>
     */
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
