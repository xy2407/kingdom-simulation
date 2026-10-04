package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 玩家档案：首登状态与归属。
 *
 * <p>它管两件事，正好是"首登弹阵营选择"和"领地挂在哪面旗下"这两条需求的落点：</p>
 * <ol>
 *   <li>{@code onboarded=false} 表示这名玩家还没走完选国流程——登录时据此弹界面；
 *       走完置 true，之后再登录不弹。</li>
 *   <li>{@code nationId} 是首登时选定的国家（自建国则是自己建的那个），
 *       {@code territoryId} 是自己那块领地；空串表示还没有。</li>
 * </ol>
 *
 * <p>以玩家 UUID 为主键，与原版 playerdata 无关，改名换皮肤都不受影响。</p>
 *
 * @param uuid         玩家 UUID
 * @param nationId     所属国家 id，空串表示未选
 * @param territoryId  自己的领地 id，空串表示未建
 * @param onboarded    是否已走完首登流程
 * @param firstJoinDay 首次进入时的世界天数
 */
public record PlayerProfile(UUID uuid, String nationId, String territoryId,
                            boolean onboarded, int firstJoinDay) {

    public PlayerProfile {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
    }

    /** 新玩家第一次进存档时的初始档案：什么都没选，也没走完流程。 */
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
