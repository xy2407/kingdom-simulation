package com.xy2407.kingdom.common.npc;

import net.minecraft.util.RandomSource;

/**
 * NPC 天赋：三项上限、出生随机区间与特殊个体概率，目前仅用于身份卡展示
 */
public final class NpcTalent {

    public static final int INTELLIGENCE_MAX = 200;
    public static final int STRENGTH_MAX = 400;
    public static final int AGILITY_MAX = 200;

    public static final String TODO_FUTURE_USE = "天赋作用尚未定义";

    private static final int INTELLIGENCE_ROLL_MIN = 40;
    private static final int INTELLIGENCE_ROLL_MAX = 70;
    private static final int AGILITY_ROLL_MIN = 40;
    private static final int AGILITY_ROLL_MAX = 70;
    private static final int STRENGTH_ROLL_MIN = 50;
    private static final int STRENGTH_ROLL_MAX = 100;

    private static final int SPECIAL_STRENGTH_MIN = 150;
    private static final int SPECIAL_STRENGTH_MAX = 240;
    private static final int SPECIAL_ODDS = 24;

    private NpcTalent() {
    }

    public static void roll(NpcData data, RandomSource random) {
        if (data == null || random == null) {
            return;
        }
        data.setIntelligence(rollRange(random, INTELLIGENCE_ROLL_MIN, INTELLIGENCE_ROLL_MAX));
        data.setAgility(rollRange(random, AGILITY_ROLL_MIN, AGILITY_ROLL_MAX));
        if (random.nextInt(SPECIAL_ODDS) == 0) {
            data.setStrength(rollRange(random, SPECIAL_STRENGTH_MIN, SPECIAL_STRENGTH_MAX));
        } else {
            data.setStrength(rollRange(random, STRENGTH_ROLL_MIN, STRENGTH_ROLL_MAX));
        }
    }

    private static int rollRange(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
