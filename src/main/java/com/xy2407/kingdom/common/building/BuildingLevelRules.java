package com.xy2407.kingdom.common.building;

/**
 * 建筑一到五级的数值规则，集中给出床位、田块、农田格数与各类岗位名额
 */
public final class BuildingLevelRules {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;

    private static final int[] BEDS = {3, 6, 9, 12, 15};

    private static final int[] FARM_PLOTS = {1, 3, 5, 7, 9};

    private static final int[] FARM_SLOTS = {64, 144, 256, 400, 576};

    private static final int[] RANCH_PENS = {1, 3, 5, 7, 9};

    private static final int[] INDUSTRY_PLOTS = {1, 2, 3, 4, 5};

    private static final int[] INDUSTRY_WORKERS = {3, 7, 12, 18, 25};

    private static final int[] SHOP_CLERKS = {1, 2, 3, 4, 5};

    private static final int[] SHOP_SHELVES = {3, 7, 12, 18, 25};

    private static final int[] RESTAURANT_TABLES = {3, 5, 8, 12, 18};

    private static final int[] RESTAURANT_CHAIRS = {6, 10, 16, 24, 36};

    private static final int[] MILITARY_TROOPS = {8, 16, 24, 32, 40};

    private static final int[] WAREHOUSE_COURIERS = {3, 5, 7, 9, 12};

    private static final int[] BUILDER_HUT_CREW = {1, 3, 5, 7, 9};

    private BuildingLevelRules() {
    }

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    private static int pick(int[] table, int level) {
        return table[clampLevel(level) - 1];
    }

    public static int bedCapacity(int level) {
        return pick(BEDS, level);
    }

    public static int farmPlotLimit(int level) {
        return pick(FARM_PLOTS, level);
    }

    public static int farmSlotTotal(int level) {
        return pick(FARM_SLOTS, level);
    }

    public static int ranchPenLimit(int level) {
        return pick(RANCH_PENS, level);
    }

    public static int farmSlotsOf(BuildingWork work, int level) {
        return work == BuildingWork.FARM ? farmSlotTotal(level) : 0;
    }

    public static int industryPlotLimit(int level) {
        return pick(INDUSTRY_PLOTS, level);
    }

    public static int industryWorkers(int level) {
        return pick(INDUSTRY_WORKERS, level);
    }

    public static int shopClerks(int level) {
        return pick(SHOP_CLERKS, level);
    }

    public static int shopShelves(int level) {
        return pick(SHOP_SHELVES, level);
    }

    public static int restaurantTables(int level) {
        return pick(RESTAURANT_TABLES, level);
    }

    public static int restaurantChairs(int level) {
        return pick(RESTAURANT_CHAIRS, level);
    }

    public static int militaryTroops(int level) {
        return pick(MILITARY_TROOPS, level);
    }

    public static int warehouseCouriers(int level) {
        return pick(WAREHOUSE_COURIERS, level);
    }

    public static int builderHutCrew(int level) {
        return pick(BUILDER_HUT_CREW, level);
    }
}
