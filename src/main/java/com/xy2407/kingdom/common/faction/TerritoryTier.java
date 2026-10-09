package com.xy2407.kingdom.common.faction;

/**
 * 领地等级：按领地人口划分五档，由服务端算好随地图叠层下发，避免两端各算一套阈值。
 */
public enum TerritoryTier {

    OUTPOST("据点", 0),
    VILLAGE("村落", 24),
    TOWN("集镇", 80),
    CITY("城池", 200),
    STATE("城邦", 400);

    private final String label;
    private final int need;

    TerritoryTier(String label, int need) {
        this.label = label;
        this.need = need;
    }

    public String label() {
        return label;
    }

    public int need() {
        return need;
    }

    public static TerritoryTier of(int population) {
        TerritoryTier result = OUTPOST;
        for (TerritoryTier tier : values()) {
            if (population >= tier.need) {
                result = tier;
            }
        }
        return result;
    }

    public static String labelOf(int population) {
        return of(population).label();
    }
}
