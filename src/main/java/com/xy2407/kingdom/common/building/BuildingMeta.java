package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 建筑元数据：蓝图定义里声明的分类、工种、等级、岗位与各类配额清单。
 */
public record BuildingMeta(String id, String buildName, String file, String name, BuildingType type,
                           BuildingWork work,
                           List<String> tags, int level, int jobs, List<Integer> hire,
                           int farmSlots, int storageSlots,
                           List<CropSpec> crops,
                           List<AnimalSpec> animals, List<RecipeSpec> recipes,
                           List<MenuSpec> menu,
                           List<TroopSpec> troops, int patrolRadius, String nation) {

    public BuildingMeta {
        buildName = buildName == null || buildName.isBlank() ? id : buildName;
        file = file == null || file.isBlank() ? buildName : file;
        if (type == null && work != null) {
            type = work.type();
        }
        hire = hire == null ? List.of() : List.copyOf(hire);
        tags = tags == null ? List.of() : List.copyOf(tags);
        crops = crops == null ? List.of() : List.copyOf(crops);
        animals = animals == null ? List.of() : List.copyOf(animals);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        menu = menu == null ? List.of() : List.copyOf(menu);
        troops = troops == null ? List.of() : List.copyOf(troops);
    }

    public boolean isWork(BuildingWork candidate) {
        return work != null && work == candidate;
    }

    public boolean isType(BuildingType candidate) {
        return type != null && type == candidate;
    }

    public boolean defined() {
        return type != null;
    }

    public static BuildingMeta fallback(String id, BuildingType type) {
        return new BuildingMeta(id, id, id, shortName(id), type, null, List.of(), 1, 0,
                List.of(), 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0, "");
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

    public String workLabel() {
        return work == null ? "未分类" : work.label();
    }
}
