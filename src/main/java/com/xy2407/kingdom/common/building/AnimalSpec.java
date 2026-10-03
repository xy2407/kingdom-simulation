package com.xy2407.kingdom.common.building;

/**
 * 养殖建筑能养的一种动物，对应面板上的一张卡片。
 *
 * <p>一座养殖建筑按"分栏"来养：不同动物各占一栏、互不混栏，所以清单里每种动物
 * 自带一条 {@code capacity} 上限。这一项就是 nsuk 里写死在
 * {@code RecipeDefinition#maxEntities()} 的那个值（采集 12 / 宰杀 6），
 * 这里改成由建筑 json 逐种定义。</p>
 *
 * @param id       标识，同一建筑内唯一，作面板与入库的键
 * @param name     显示名；未配置时取 id
 * @param entity   实体 id，如 {@code minecraft:cow}
 * @param capacity 这一栏的养殖上限
 * @param feed     饲料物品 id
 * @param mode     产出方式：{@code collect}（采集奶/蛋/毛）或 {@code slaughter}（屠宰取肉）
 * @param output   产出物品 id
 */
public record AnimalSpec(String id, String name, String entity, int capacity,
                         String feed, String mode, String output) {

    public AnimalSpec {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        entity = entity == null ? "" : entity;
        feed = feed == null ? "" : feed;
        mode = mode == null || mode.isBlank() ? "collect" : mode;
        output = output == null ? "" : output;
    }

    /** 是否采集型（不杀生，定期收奶/蛋/毛）。 */
    public boolean collecting() {
        return !"slaughter".equalsIgnoreCase(mode);
    }
}
