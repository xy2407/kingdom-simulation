package com.xy2407.kingdom.common.faction;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * 野生领地名单：哪些结构会被圈成野生领地、各自归哪个阵营、用什么名字与固定领主 UUID
 */
public final class WildStructureCatalog {

    public static final String DEFAULT_NATION = "valarian";

    private static final Map<String, String> NATION_BY_PATH = buildNations();

    private static final String[] NAME_POOL = {
            "青柳村", "白桦镇", "铁砧堡", "麦浪庄", "石桥村", "暮色岗", "松风寨",
            "红土窑", "寒泉屯", "长坡庄", "烟墩堡", "乌桕村", "落雁坳", "南浦寨"
    };

    private WildStructureCatalog() {
    }

    private static final Set<String> VILLAGE_IDS = Set.of(
            "minecraft:village_plains",
            "minecraft:village_desert",
            "minecraft:village_savanna",
            "minecraft:village_snowy",
            "minecraft:village_taiga");

    public static boolean matches(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        if (VILLAGE_IDS.contains(id.toString())) {
            return true;
        }
        String namespace = id.getNamespace();
        String path = id.getPath();
        if ("joshie".equals(namespace) && "village_ocean".equals(path)) {
            return true;
        }
        return "valarian_conquest".equals(namespace) && path.endsWith("_castle");
    }

    public static String nationFor(ResourceLocation id) {
        if (id == null) {
            return DEFAULT_NATION;
        }
        String key = baseKey(id);
        return NATION_BY_PATH.getOrDefault(key, DEFAULT_NATION);
    }

    public static UUID ownerFor(String instanceKey) {
        return UUID.nameUUIDFromBytes(
                ("xy2407:wild:" + instanceKey).getBytes(StandardCharsets.UTF_8));
    }

    public static String nameFor(RandomSource random) {
        return NAME_POOL[random.nextInt(NAME_POOL.length)];
    }

    private static String baseKey(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith("village_")) {
            return path.substring("village_".length());
        }
        return path;
    }

    private static Map<String, String> buildNations() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("plains", "bohemian");
        map.put("desert", "numerian");
        map.put("savanna", "galician");
        map.put("snowy", "visgothian");
        map.put("taiga", "caladrian");
        map.put("ocean", "lancastian");
        map.put("castle", "templar");
        map.put("aegis_castle", "templar");
        map.put("barathian_castle", "barathian");
        map.put("burgundian_castle", "burgundian");
        map.put("hospitaller_castle", "hospitaller");
        map.put("orleanian_castle", "orleanian");
        map.put("templar_castle", "templar");
        map.put("valarian_castle", "valarian");
        map.put("visgothian_castle", "visgothian");
        return map;
    }
}
