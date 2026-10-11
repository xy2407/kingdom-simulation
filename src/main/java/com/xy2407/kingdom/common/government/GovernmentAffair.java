package com.xy2407.kingdom.common.government;

/**
 * 一条政务实例：绑定领地、类型与创建、过期的游戏天
 */
public record GovernmentAffair(String id, String territoryId, String type, int createdDay, int expireDay) {

    public static final int LIFETIME_DAYS = 4;

    public GovernmentAffairType typeOf() {
        return GovernmentAffairType.byName(type);
    }

    public int daysLeft(int today) {
        return Math.max(0, expireDay - today);
    }
}
