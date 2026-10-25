package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 建筑元数据：与 nbt 同名的 json 里声明的那部分信息（类型、等级、岗位、各类配额与清单）。
 */
public record BuildingMeta(String id, String name, BuildingType type,
                           List<String> tags, int level, int jobs, List<Integer> hire,
                           int farmSlots, int storageSlots,
                           List<CropSpec> crops,
                           List<AnimalSpec> animals, List<RecipeSpec> recipes,
                           String subtype, List<MenuSpec> menu,
                           List<TroopSpec> troops, int patrolRadius, String nation,
                           IndustryKind industryKind) {

    public static final String SUBTYPE_RESTAURANT = "restaurant";
    public static final String SUBTYPE_SHOP = "shop";

    public BuildingMeta {
        hire = hire == null ? List.of() : List.copyOf(hire);
        tags = tags == null ? List.of() : List.copyOf(tags);
        crops = crops == null ? List.of() : List.copyOf(crops);
        animals = animals == null ? List.of() : List.copyOf(animals);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        menu = menu == null ? List.of() : List.copyOf(menu);
        troops = troops == null ? List.of() : List.copyOf(troops);
    }

    public boolean isRestaurant() {
        return SUBTYPE_RESTAURANT.equalsIgnoreCase(subtype);
    }

    public boolean isShop() {
        return SUBTYPE_SHOP.equalsIgnoreCase(subtype);
    }

    public static BuildingMeta fallback(String id, BuildingType type) {
        return new BuildingMeta(id, shortName(id), type, List.of(), 1, 0, List.of(), 0, 0, List.of(),
                List.of(), List.of(), "", List.of(), List.of(), 0, "", null);
    }

    public int hireLimit() {
        if (hire.isEmpty()) {
            return Math.max(0, jobs);
        }
        int index = Math.max(0, Math.min(hire.size() - 1, level - 1));
        return Math.max(0, hire.get(index));
    }

    public static String shortName(String id) {
        if (id == null) {
            return "";
        }
        int slash = Math.max(id.lastIndexOf('/'), id.lastIndexOf('\\'));
        return slash < 0 ? id : id.substring(slash + 1);
    }

    public String typeLabel() {
        return type == null ? "未分类" : type.label();
    }

    public String industryKindLabel() {
        return industryKind == null ? "未分类" : industryKind.label();
    }
}
