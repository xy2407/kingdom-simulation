package com.xy2407.kingdom.common.government;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.faction.TerritoryTier;

/**
 * 政务类型池：分对内与对外两类，各自标注解锁所需的最低领地等级； TODO 详细内容与结算待后续独立系统实现
 */
public enum GovernmentAffairType {

    VILLAGER_REQUEST("村民请求", Scope.INTERNAL, TerritoryTier.VILLAGE),
    BANDIT_ENCOUNTER("遭遇盗贼", Scope.INTERNAL, TerritoryTier.VILLAGE),
    VILLAGER_DISPUTE("村民纷争", Scope.INTERNAL, TerritoryTier.VILLAGE),
    CASE_INVESTIGATION("案件侦破", Scope.INTERNAL, TerritoryTier.VILLAGE),
    TOWN_SECURITY("城镇治安", Scope.INTERNAL, TerritoryTier.TOWN),
    PRISONER_DISPOSAL("犯人处置", Scope.INTERNAL, TerritoryTier.TOWN),
    DEFECTOR_DISPOSAL("叛逃市民处置", Scope.INTERNAL, TerritoryTier.CITY),
    SPY_DISPOSAL("间谍处置", Scope.INTERNAL, TerritoryTier.CITY),
    CARAVAN_VISIT("商队来访", Scope.EXTERNAL, TerritoryTier.VILLAGE),
    REFUGEE_REQUEST("难民请求", Scope.EXTERNAL, TerritoryTier.VILLAGE),
    BANDIT_INVASION("强盗入侵", Scope.EXTERNAL, TerritoryTier.VILLAGE),
    MATERIAL_AID("物资援助", Scope.EXTERNAL, TerritoryTier.TOWN),
    IMMIGRATION_APPLICATION("移民申请", Scope.EXTERNAL, TerritoryTier.TOWN),
    FOREIGN_TRADE("外贸关系", Scope.EXTERNAL, TerritoryTier.TOWN),
    ORDER_REQUEST("订单请求", Scope.EXTERNAL, TerritoryTier.TOWN),
    SLAVE_MERCHANT("奴隶商人", Scope.EXTERNAL, TerritoryTier.TOWN),
    VILLAGE_TRIBUTE("村镇上供", Scope.EXTERNAL, TerritoryTier.CITY),
    ALLIANCE_PACT("建立盟约", Scope.EXTERNAL, TerritoryTier.CITY),
    SUPPORT_REQUEST("支援请求", Scope.EXTERNAL, TerritoryTier.CITY);

    public enum Scope {
        INTERNAL("对内"),
        EXTERNAL("对外");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String label;
    private final Scope scope;
    private final TerritoryTier unlockTier;

    GovernmentAffairType(String label, Scope scope, TerritoryTier unlockTier) {
        this.label = label;
        this.scope = scope;
        this.unlockTier = unlockTier;
    }

    public String label() {
        return label;
    }

    public Scope scope() {
        return scope;
    }

    public TerritoryTier unlockTier() {
        return unlockTier;
    }

    public boolean unlockedAt(TerritoryTier tier) {
        return tier != null && tier.ordinal() >= unlockTier.ordinal();
    }

    public static List<GovernmentAffairType> unlockedFor(TerritoryTier tier) {
        List<GovernmentAffairType> pool = new ArrayList<>();
        for (GovernmentAffairType type : values()) {
            if (type.unlockedAt(tier)) {
                pool.add(type);
            }
        }
        return pool;
    }

    public static GovernmentAffairType byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        for (GovernmentAffairType type : values()) {
            if (type.name().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}
