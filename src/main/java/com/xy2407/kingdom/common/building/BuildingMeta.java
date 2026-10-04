package com.xy2407.kingdom.common.building;

import java.util.List;

/**
 * 建筑元数据。
 *
 * <p>默认从与 {@code .nbt} 同名的 {@code .json} 读取；文件缺失、字段缺省或解析失败时逐项兜底，
 * 类型再退回"所在子目录名"。因此给一个蓝图只放 nbt 也能用，只是元数据比较简陋。</p>
 *
 * <p>这里只放<b>无法从方块推断</b>的信息。床位因此不在此列——它是蓝图里画出来的客观属性
 * （一张床由 foot / head 两个方块组成，只数 foot），由解析 NBT 时统计，见
 * {@code KingdomSimulationStructures.Entry#beds()}。容量随时能从蓝图重算，不必入库，也不必手填。</p>
 *
 * @param id        蓝图标识：相对 {@code structures} 的路径去掉扩展名，如 {@code residential/house_small}。
 *                  它同时是加载蓝图、网络传输与入库用的键。
 * @param name      显示名；未配置时取 {@code id} 的最后一段
 * @param type      建筑类型；无法判定时为 {@code null}（表示"未归类"）
 * @param tags      标签，可空。二级分类也走这里，不再单设 category
 * @param level     等级
 * @param jobs      提供的岗位数（商业 / 工业 / 养殖 / 农业 / 军事类建筑使用）
 * @param farmSlots   种植格上限，即这座建筑最多能划出多少格农田；非农业建筑为 0。
 *                    与 {@code jobs} 是两回事：{@code jobs} 是岗位数，这里是土地额度，
 *                    农田面板把总额度分配给各块农田，见 {@code FarmPlot}。
 * @param crops       农业建筑能种的作物清单，面板照这份清单把作物列成槽位；非农业建筑为空，
 *                    留空的农业建筑由服务端退回全局默认清单，见 {@link CropSpec}。
 * @param animals     养殖建筑能养的动物清单，每种一栏、各自带上限；非养殖建筑为空。
 *                    面板照这份清单把动物列成卡片，见 {@link AnimalSpec}。
 * @param recipes     工业建筑可选的生产配方清单，同时只跑一个；非工业建筑为空。
 *                    面板照这份清单把配方列成卡片，见 {@link RecipeSpec}。
 * @param subtype     商业建筑的子类：{@code restaurant}（餐厅）或 {@code shop}（出售铺）。
 *                    其它建筑留空；两类商业的面板与数据走向完全不同，靠它分流。
 * @param menu        餐厅的菜单，面板照这份清单把菜品列成卡片；见 {@link MenuSpec}。
 *                    出售铺不上菜，为空——它上架的物品来自玩家背包，属于运行时数据。
 * @param troops      军事建筑的驻军编制清单，每种兵各自带上限与军饷；非军事建筑为空。
 *                    面板照这份清单把兵种列成卡片，见 {@link TroopSpec}。
 * @param patrolRadius 巡逻半径（格）：驻军在建筑周围多远巡防，0 表示只在建筑内值守。
 * @param nation      归属势力，可空
 */
public record BuildingMeta(String id, String name, BuildingType type,
                           List<String> tags, int level, int jobs, int farmSlots,
                           List<CropSpec> crops,
                           List<AnimalSpec> animals, List<RecipeSpec> recipes,
                           String subtype, List<MenuSpec> menu,
                           List<TroopSpec> troops, int patrolRadius, String nation) {

    /** 商业子类：餐厅。 */
    public static final String SUBTYPE_RESTAURANT = "restaurant";
    /** 商业子类：出售铺。 */
    public static final String SUBTYPE_SHOP = "shop";

    public BuildingMeta {
        tags = tags == null ? List.of() : List.copyOf(tags);
        crops = crops == null ? List.of() : List.copyOf(crops);
        animals = animals == null ? List.of() : List.copyOf(animals);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        menu = menu == null ? List.of() : List.copyOf(menu);
        troops = troops == null ? List.of() : List.copyOf(troops);
    }

    /** 是否餐厅类商业。 */
    public boolean isRestaurant() {
        return SUBTYPE_RESTAURANT.equalsIgnoreCase(subtype);
    }

    /** 是否出售铺类商业。 */
    public boolean isShop() {
        return SUBTYPE_SHOP.equalsIgnoreCase(subtype);
    }

    public static BuildingMeta fallback(String id, BuildingType type) {
        return new BuildingMeta(id, shortName(id), type, List.of(), 1, 0, 0, List.of(),
                List.of(), List.of(), "", List.of(), List.of(), 0, "");
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
}
