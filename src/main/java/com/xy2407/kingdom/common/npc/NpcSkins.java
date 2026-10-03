package com.xy2407.kingdom.common.npc;

import java.util.Locale;
import java.util.UUID;

/**
 * 按性别与市民编号稳定分配皮肤文件名，男女各按贴图数量取模
 */
public final class NpcSkins {
    public static final int FEMALE_VARIANTS = 61;
    public static final int MALE_VARIANTS = 1;
    public static final String DEFAULT_SKIN = "male1";

    private NpcSkins() {
    }

    public static String assign(String gender, UUID id) {
        boolean female = isFemale(gender);
        int variants = Math.max(1, female ? FEMALE_VARIANTS : MALE_VARIANTS);
        int index = Math.floorMod(id == null ? 0 : id.hashCode(), variants) + 1;
        return (female ? "female" : "male") + index;
    }

    public static boolean isFemale(String gender) {
        if (gender == null) {
            return false;
        }
        String value = gender.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("F") || value.equals("女") || value.equals("女性");
    }
}
