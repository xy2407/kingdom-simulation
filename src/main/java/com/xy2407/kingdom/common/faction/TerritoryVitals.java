package com.xy2407.kingdom.common.faction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一块领地的民心三值：凝聚力 / 忠诚度 / 幸福度，外加一份战争快照。
 *
 * <p><b>为什么和 {@link Territory} 分开、而且是可变的。</b>领地的地界、归属、创建日
 * 一旦定下就不再变，所以 {@code Territory} 是 record；而这三个值每次有人死亡、
 * 每次日结都可能变。塞进 record 就得频繁重建整条记录并覆盖注册表，
 * 得不偿失。这里做成独立的可变对象，由 {@code TerritoryVitalsStore} 按领地 id 持有，
 * 定期增量落库（表 {@code territory_vitals}，主键 = 领地 id）。</p>
 *
 * <p><b>取值一律夹在 0~100</b>，任何加减都走 {@link #addUnity} 这类方法，
 * 不允许直接写字段，避免出现 120% 或者负数。</p>
 *
 * <p><b>战争部分目前是"骨架"。</b>项目里还没有战争系统，所以
 * {@link #beginWar}/{@link #endWar} 没有任何调用方——但凝聚力规则
 * （开战快照人口、按伤亡扣减、低于阈值投降）全部写在这里，
 * 等战争系统接上后直接调用即可，不必回头改规则。</p>
 */
public final class TerritoryVitals {

    /** 凝聚力默认值：非战争状态也是 100%。 */
    public static final int UNITY_DEFAULT = 100;
    /** 忠诚度默认值（创建领地时）。 */
    public static final int LOYALTY_DEFAULT = 80;
    /** 幸福度默认值（创建领地时）。 */
    public static final int HAPPINESS_DEFAULT = 80;

    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 100;

    /** 凝聚力低于此值、且处于战争状态 → 直接投降。 */
    public static final int SURRENDER_THRESHOLD = 15;

    /** 主动开战方在开战瞬间额外扣除的凝聚力。 */
    public static final int AGGRESSOR_PENALTY = 10;

    /** 领地里出现死亡或失踪时，凝聚力的随机增减幅度（±）。 */
    public static final int CASUALTY_SWING = 20;

    private final String territoryId;

    private int unity = UNITY_DEFAULT;
    private int loyalty = LOYALTY_DEFAULT;
    private int happiness = HAPPINESS_DEFAULT;

    // ------------------------------------------------------------------ 战争快照
    /* 没有战争时 enemyId 为空串。这四个数只在开战那一刻记一次：
       凝聚力扣减按"开战人口"当分母，之后新增的人口不参与计算。 */
    private String warEnemyId = "";
    private int warStartDay;
    private int warSelfPopulation;
    private int warEnemyPopulation;
    /** 这场战争是不是本方主动发起的（主动方开战即扣 {@link #AGGRESSOR_PENALTY}）。 */
    private boolean warAggressor;

    public TerritoryVitals(String territoryId) {
        this.territoryId = territoryId == null ? "" : territoryId;
    }

    /** 从库里读出来的那份（含三个值与战争快照）。 */
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

    // ------------------------------------------------------------------ 战争

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

    /**
     * 开战：记下"开战瞬间"双方领地的<b>人口</b>，并给主动方扣 {@link #AGGRESSOR_PENALTY}。
     *
     * <p>人口只在这里记一次，之后 {@link #unityAfterCasualties} 全部以它当分母——
     * 这正是"增加人口不进行计算"的落点：分母锁死在开战那一刻。</p>
     *
     * @param enemyTerritoryId 敌方领地 id
     * @param day              开战时的世界天数
     * @param selfPopulation   开战瞬间本方领地人口
     * @param enemyPopulation  开战瞬间敌方领地人口
     * @param aggressor        本方是否为主动开战方
     */
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

    /** 结束战争（停战 / 一方覆灭 / 投降）。快照清零。 */
    public void endWar() {
        warEnemyId = "";
        warStartDay = 0;
        warSelfPopulation = 0;
        warEnemyPopulation = 0;
        warAggressor = false;
    }

    /**
     * 按"净减员占开战人口的百分比"扣凝聚力。
     *
     * <p>口径：{@code 减员 = 开战人口 − 当前人口}，只取正值——
     * <b>人口增加不参与计算</b>（增了也不抵消已有的伤亡），
     * 开战人口为 0 时不做任何扣减（没法算比例）。</p>
     *
     * @param currentPopulation 当前本方领地人口
     * @return 本次实际扣掉的凝聚力量（0 表示没扣）
     */
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

    /** 该领地里有人死亡/失踪时，凝聚力随机 ±{@link #CASUALTY_SWING}。 */
    public int randomCasualtySwing(boolean increase) {
        int delta = increase ? CASUALTY_SWING : -CASUALTY_SWING;
        addUnity(delta);
        return delta;
    }

    /** 是否已经跌破投降线、且正处于战争中。 */
    public boolean shouldSurrender() {
        return atWar() && unity < SURRENDER_THRESHOLD;
    }

    // ------------------------------------------------------------------ 持久化

    /** 落库用的列 -> 值。列名与 {@code TerritoryVitalsSchema} 一一对应。 */
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
