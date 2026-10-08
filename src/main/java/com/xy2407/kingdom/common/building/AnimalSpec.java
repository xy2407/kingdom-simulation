package com.xy2407.kingdom.common.building;

/**
 * 养殖建筑能养的一种动物，对应面板上的一张卡片：实体、存栏上限、饲料与产出方式。
 */
public record AnimalSpec(String id, String name, String entity, int capacity,
                         String feed, String mode, String output) {

    public static final int DEFAULT_CAPACITY = 24;

    public AnimalSpec {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        entity = entity == null ? "" : entity;
        feed = feed == null ? "" : feed;
        mode = mode == null || mode.isBlank() ? "collect" : mode;
        output = output == null ? "" : output;
    }

    public boolean collecting() {
        return !"slaughter".equalsIgnoreCase(mode);
    }

    public int limit() {
        return capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }
}
