package com.xy2407.kingdom.common.faction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一块领地的民心三值：凝聚力、忠诚度、幸福度，外加一份战争快照。
 */
public final class TerritoryVitals {

    public static final int UNITY_DEFAULT = 100;
    public static final int LOYALTY_DEFAULT = 80;
    public static final int HAPPINESS_DEFAULT = 80;

    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 100;

    public static final int SURRENDER_THRESHOLD = 15;

    public static final int AGGRESSOR_PENALTY = 10;

    public static final int CASUALTY_SWING = 20;

    private final String territoryId;

    private int unity = UNITY_DEFAULT;
    private int loyalty = LOYALTY_DEFAULT;
    private int happiness = HAPPINESS_DEFAULT;

    private String warEnemyId = "";
    private int warStartDay;
    private int warSelfPopulation;
    private int warEnemyPopulation;
    private boolean warAggressor;

    public TerritoryVitals(String territoryId) {
        this.territoryId = territoryId == null ? "" : territoryId;
    }

    public TerritoryVitals(String territoryId, int unity, int loyalty, int happiness,
                           String warEnemyId, int warStartDay,
                           int warSelfPopulation, int warEnemyPopulation, boolean warAggressor) {
        this(territoryId);
        this.unity = clamp(unity);
        this.loyalty = clamp(loyalty);
        this.happiness = clamp(happiness);
        this.warEnemyId = warEnemyId == null ? "" : warEnemyId;
        this.warStartDay = warStartDay;
        this.warSelfPopulation = Math.max(0, warSelfPopulation);
        this.warEnemyPopulation = Math.max(0, warEnemyPopulation);
        this.warAggressor = warAggressor;
    }

    public String territoryId() {
        return territoryId;
    }

    public int unity() {
        return unity;
    }

    public int loyalty() {
        return loyalty;
    }

    public int happiness() {
        return happiness;
    }

    public void setUnity(int value) {
        unity = clamp(value);
    }

    public void setLoyalty(int value) {
        loyalty = clamp(value);
    }

    public void setHappiness(int value) {
        happiness = clamp(value);
    }

    public void addUnity(int delta) {
        setUnity(unity + delta);
    }

    public void addLoyalty(int delta) {
        setLoyalty(loyalty + delta);
    }

    public void addHappiness(int delta) {
        setHappiness(happiness + delta);
    }


    public boolean atWar() {
        return !warEnemyId.isBlank();
    }

    public String warEnemyId() {
        return warEnemyId;
    }

    public int warStartDay() {
        return warStartDay;
    }

    public int warSelfPopulation() {
        return warSelfPopulation;
    }

    public int warEnemyPopulation() {
        return warEnemyPopulation;
    }

    public boolean warAggressor() {
        return warAggressor;
    }

    public void beginWar(String enemyTerritoryId, int day,
                         int selfPopulation, int enemyPopulation, boolean aggressor) {
        this.warEnemyId = enemyTerritoryId == null ? "" : enemyTerritoryId;
        this.warStartDay = day;
        this.warSelfPopulation = Math.max(0, selfPopulation);
        this.warEnemyPopulation = Math.max(0, enemyPopulation);
        this.warAggressor = aggressor;
        if (aggressor) {
            addUnity(-AGGRESSOR_PENALTY);
        }
    }

    public void endWar() {
        warEnemyId = "";
        warStartDay = 0;
        warSelfPopulation = 0;
        warEnemyPopulation = 0;
        warAggressor = false;
    }

    public int unityLossFromCasualties(int currentPopulation) {
        if (!atWar() || warSelfPopulation <= 0) {
            return 0;
        }
        int casualties = warSelfPopulation - Math.max(0, currentPopulation);
        if (casualties <= 0) {
            return 0;
        }
        int loss = (int) Math.round(casualties * 100.0d / warSelfPopulation);
        if (loss <= 0) {
            return 0;
        }
        addUnity(-loss);
        return loss;
    }

    public int randomCasualtySwing(boolean increase) {
        int delta = increase ? CASUALTY_SWING : -CASUALTY_SWING;
        addUnity(delta);
        return delta;
    }

    public boolean shouldSurrender() {
        return atWar() && unity < SURRENDER_THRESHOLD;
    }


    public Map<String, Object> toValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_TERRITORY_ID, territoryId);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_UNITY, unity);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_LOYALTY, loyalty);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_HAPPINESS, happiness);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_WAR_ENEMY_ID, warEnemyId);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_WAR_START_DAY, warStartDay);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_WAR_SELF_POP, warSelfPopulation);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_WAR_ENEMY_POP, warEnemyPopulation);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_WAR_AGGRESSOR, warAggressor ? 1 : 0);
        values.put(com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static int clamp(int value) {
        if (value < MIN_VALUE) {
            return MIN_VALUE;
        }
        return Math.min(value, MAX_VALUE);
    }

    @Override
    public String toString() {
        return "TerritoryVitals[" + territoryId + " 凝聚力=" + unity
                + " 忠诚=" + loyalty + " 幸福=" + happiness
                + (atWar() ? " 战争=" + warEnemyId : "") + "]";
    }
}
