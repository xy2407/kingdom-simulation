package com.xy2407.kingdom.common.building;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 工种目录，定义各分类可选工种及每个工种的岗位、配方与兵种默认参数
 */
public final class BuildingWorkCatalog {

    private static final Map<BuildingType, List<BuildingWork>> BY_TYPE = new EnumMap<>(BuildingType.class);

    private static final Map<BuildingWork, BuildingMeta> TEMPLATES = new EnumMap<>(BuildingWork.class);

    static {
        BY_TYPE.put(BuildingType.RESIDENTIAL, List.of(BuildingWork.RESIDENCE));
        BY_TYPE.put(BuildingType.AGRICULTURE, List.of(BuildingWork.FARM));
        BY_TYPE.put(BuildingType.LIVESTOCK, List.of(BuildingWork.RANCH));
        BY_TYPE.put(BuildingType.INDUSTRY, List.of(
                BuildingWork.LUMBER, BuildingWork.MINE, BuildingWork.WORKSHOP,
                BuildingWork.FORGE, BuildingWork.CHEESE, BuildingWork.BREWERY));
        BY_TYPE.put(BuildingType.COMMERCIAL, List.of(
                BuildingWork.RESTAURANT, BuildingWork.SHOP));
        BY_TYPE.put(BuildingType.MILITARY, List.of(
                BuildingWork.BARRACKS));

        for (BuildingWork work : BuildingWork.values()) {
            TEMPLATES.put(work, build(work));
        }
    }

    private BuildingWorkCatalog() {
    }

    public static List<BuildingWork> worksOf(BuildingType type) {
        if (type == null) {
            return List.of();
        }
        return BY_TYPE.getOrDefault(type, List.of());
    }

    public static boolean needsChoice(BuildingType type) {
        return worksOf(type).size() > 1;
    }

    public static BuildingWork soleWork(BuildingType type) {
        List<BuildingWork> works = worksOf(type);
        return works.size() == 1 ? works.get(0) : null;
    }

    public static boolean accepts(BuildingType type, BuildingWork work) {
        return work != null && worksOf(type).contains(work);
    }

    public static BuildingMeta metaOf(BuildingWork work, int level) {
        if (work == null) {
            return BuildingMeta.fallback("", null);
        }
        BuildingMeta template = TEMPLATES.get(work);
        if (template == null) {
            return BuildingMeta.fallback(work.name().toLowerCase(java.util.Locale.ROOT), work.type());
        }
        return withLevel(template, work, level);
    }

    public static BuildingMeta withLevel(BuildingMeta template, BuildingWork work, int level) {
        if (template == null) {
            return metaOf(work, level);
        }
        int actualLevel = Math.max(1, level);
        List<TroopSpec> troops = work == BuildingWork.BARRACKS
                ? fitTroops(template.troops(), BuildingLevelRules.militaryTroops(actualLevel))
                : template.troops();
        return new BuildingMeta(template.id(), template.buildName(), template.file(),
                template.name(), template.type(), work, template.tags(), actualLevel, template.jobs(),
                template.hire(), template.farmSlots(), template.storageSlots(),
                template.crops(), template.animals(), template.recipes(), template.menu(),
                troops, template.patrolRadius(), template.nation());
    }

    private static List<TroopSpec> fitTroops(List<TroopSpec> troops, int total) {
        if (troops == null || troops.isEmpty()) {
            return List.of();
        }
        int sum = 0;
        for (TroopSpec spec : troops) {
            sum += spec.capacity();
        }
        if (sum <= total) {
            return troops;
        }
        List<TroopSpec> fitted = new ArrayList<>(troops.size());
        int left = total;
        for (TroopSpec spec : troops) {
            int capacity = Math.min(spec.capacity(), left);
            left -= capacity;
            fitted.add(new TroopSpec(spec.id(), spec.name(), capacity, spec.weapon(),
                    spec.armor(), spec.wage(), spec.ranged()));
        }
        return fitted;
    }

    public static String labelOf(BuildingWork work) {
        return work == null ? "未分类" : work.label();
    }

    private static BuildingMeta build(BuildingWork work) {
        String id = work.name().toLowerCase(java.util.Locale.ROOT);
        return switch (work) {
            case RESIDENCE -> meta(id, "住宅", work, 0, List.of(), 0, 9,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case FARM -> meta(id, "农田", work, 2, List.of(2, 4, 8), 0, 54,
                    crops(), List.of(), List.of(), List.of(), List.of(), 0);
            case RANCH -> meta(id, "养殖栏", work, 1, List.of(1, 2, 4), 0, 54,
                    List.of(), animals(), List.of(), List.of(), List.of(), 0);
            case BARRACKS -> meta(id, "军营", work, 2, List.of(2, 4, 8), 0, 27,
                    List.of(), List.of(), List.of(), List.of(), troops(), 16);
            case RESTAURANT -> meta(id, "餐厅", work, 2, List.of(2, 4, 8), 0, 18,
                    List.of(), List.of(), List.of(), menu(), List.of(), 0);
            case SHOP -> meta(id, "商铺", work, 2, List.of(2, 4, 8), 0, 18,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case LUMBER -> meta(id, "伐木场", work, 2, List.of(2, 4, 8), 0, 27,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case MINE -> meta(id, "矿井", work, 2, List.of(2, 4, 8), 0, 54,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case WORKSHOP -> meta(id, "加工坊", work, 3, List.of(3, 6, 9), 0, 27,
                    List.of(), List.of(), workshopRecipes(), List.of(), List.of(), 0);
            case FORGE -> meta(id, "锻造坊", work, 3, List.of(3, 6, 9), 0, 27,
                    List.of(), List.of(), forgeRecipes(), List.of(), List.of(), 0);
            case CHEESE -> meta(id, "奶酪坊", work, 2, List.of(2, 4, 8), 0, 27,
                    List.of(), List.of(), cheeseRecipes(), List.of(), List.of(), 0);
            case BREWERY -> meta(id, "酿酒坊", work, 2, List.of(2, 4, 8), 0, 27,
                    List.of(), List.of(), breweryRecipes(), List.of(), List.of(), 0);
            case BUILDER_HUT -> meta(id, "建筑工棚", work, 0, List.of(), 0, 27,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case WAREHOUSE -> meta(id, "仓库", work, 2, List.of(2, 4, 8), 0, 54,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case CITY_HALL -> meta(id, "市政厅", work, 0, List.of(), 0, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case HOSPITAL -> meta(id, "医院", work, 2, List.of(2, 4, 8), 0, 18,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
            case SCHOOL -> meta(id, "学校", work, 2, List.of(2, 4, 8), 0, 18,
                    List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        };
    }

    private static BuildingMeta meta(String id, String name, BuildingWork work, int jobs,
                                     List<Integer> hire, int farmSlots, int storageSlots,
                                     List<CropSpec> crops, List<AnimalSpec> animals,
                                     List<RecipeSpec> recipes, List<MenuSpec> menu,
                                     List<TroopSpec> troops, int patrolRadius) {
        return new BuildingMeta(id, id, id, name, work.type(), work, List.of(), 1, jobs, hire,
                farmSlots, storageSlots, crops, animals, recipes, menu, troops, patrolRadius, "");
    }

    private static List<CropSpec> crops() {
        return List.of(
                new CropSpec("minecraft:wheat", "小麦"),
                new CropSpec("minecraft:carrot", "胡萝卜"),
                new CropSpec("minecraft:potato", "马铃薯"),
                new CropSpec("minecraft:beetroot", "甜菜根"),
                new CropSpec("minecraft:pumpkin", "南瓜"),
                new CropSpec("minecraft:melon", "西瓜"),
                new CropSpec("minecraft:torchflower_seeds", "火把花"),
                new CropSpec("minecraft:pitcher_pod", "瓶子草"),
                new CropSpec("minecraft:sugar_cane", "甘蔗"),
                new CropSpec("minecraft:sweet_berries", "甜浆果"),
                new CropSpec("minecraft:bamboo", "竹子"));
    }

    private static List<AnimalSpec> animals() {
        return List.of(
                new AnimalSpec("cow", "牛", "minecraft:cow", 12, "minecraft:wheat", "collect",
                        "minecraft:milk_bucket"),
                new AnimalSpec("sheep", "绵羊", "minecraft:sheep", 10, "minecraft:wheat", "collect",
                        "minecraft:white_wool"),
                new AnimalSpec("chicken", "鸡", "minecraft:chicken", 16, "minecraft:wheat_seeds",
                        "collect", "minecraft:egg"),
                new AnimalSpec("pig", "猪", "minecraft:pig", 8, "minecraft:carrot", "slaughter",
                        "minecraft:porkchop"));
    }

    private static List<TroopSpec> troops() {
        return List.of(
                new TroopSpec("militia", "民兵", 6, "minecraft:iron_sword",
                        "minecraft:chainmail_chestplate", 1, false),
                new TroopSpec("archer", "弓手", 3, "minecraft:bow",
                        "minecraft:leather_chestplate", 2, true));
    }

    private static List<MenuSpec> menu() {
        return List.of(
                new MenuSpec("stew", "炖牛肉",
                        List.of(new ItemCount("minecraft:beef", 2), new ItemCount("minecraft:carrot", 1)),
                        new ItemCount("minecraft:cooked_beef", 1), 8),
                new MenuSpec("bread", "烤面包",
                        List.of(new ItemCount("minecraft:wheat", 3)),
                        new ItemCount("minecraft:bread", 1), 3));
    }

    private static List<RecipeSpec> workshopRecipes() {
        List<RecipeSpec> list = new ArrayList<>();
        list.add(new RecipeSpec("oak_planks", "橡木木板",
                List.of(new ItemCount("minecraft:oak_log", 1)),
                List.of(new ItemCount("minecraft:oak_planks", 4)), 200));
        list.add(new RecipeSpec("stick", "木棍",
                List.of(new ItemCount("minecraft:oak_planks", 2)),
                List.of(new ItemCount("minecraft:stick", 4)), 120));
        list.add(new RecipeSpec("chest", "箱子",
                List.of(new ItemCount("minecraft:oak_planks", 8)),
                List.of(new ItemCount("minecraft:chest", 1)), 300));
        return list;
    }

    private static List<RecipeSpec> forgeRecipes() {
        List<RecipeSpec> list = new ArrayList<>();
        list.add(new RecipeSpec("iron_block", "铁块",
                List.of(new ItemCount("minecraft:iron_ingot", 9)),
                List.of(new ItemCount("minecraft:iron_block", 1)), 240));
        list.add(new RecipeSpec("iron_sword", "铁剑",
                List.of(new ItemCount("minecraft:iron_ingot", 2),
                        new ItemCount("minecraft:stick", 1)),
                List.of(new ItemCount("minecraft:iron_sword", 1)), 400));
        list.add(new RecipeSpec("iron_pickaxe", "铁镐",
                List.of(new ItemCount("minecraft:iron_ingot", 3),
                        new ItemCount("minecraft:stick", 2)),
                List.of(new ItemCount("minecraft:iron_pickaxe", 1)), 400));
        return list;
    }

    private static List<RecipeSpec> cheeseRecipes() {
        List<RecipeSpec> list = new ArrayList<>();
        list.add(new RecipeSpec("bread", "面包",
                List.of(new ItemCount("minecraft:wheat", 3)),
                List.of(new ItemCount("minecraft:bread", 1)), 200));
        list.add(new RecipeSpec("cake", "蛋糕",
                List.of(new ItemCount("minecraft:milk_bucket", 3),
                        new ItemCount("minecraft:sugar", 2),
                        new ItemCount("minecraft:wheat", 3),
                        new ItemCount("minecraft:egg", 1)),
                List.of(new ItemCount("minecraft:cake", 1)), 400));
        return list;
    }

    private static List<RecipeSpec> breweryRecipes() {
        List<RecipeSpec> list = new ArrayList<>();
        list.add(new RecipeSpec("sugar", "糖",
                List.of(new ItemCount("minecraft:sugar_cane", 1)),
                List.of(new ItemCount("minecraft:sugar", 1)), 160));
        list.add(new RecipeSpec("honey_bottle", "蜂蜜瓶",
                List.of(new ItemCount("minecraft:honey_block", 1),
                        new ItemCount("minecraft:glass_bottle", 4)),
                List.of(new ItemCount("minecraft:honey_bottle", 4)), 320));
        return list;
    }
}
