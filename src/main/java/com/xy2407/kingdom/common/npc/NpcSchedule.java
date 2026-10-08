package com.xy2407.kingdom.common.npc;

/**
 * 昼夜排班表，按班次与时刻查询市民的目标子状态。
 */
public final class NpcSchedule {
    public static final int DAY_LENGTH = 24000;
    public static final int NIGHT_START = 13000;
    public static final int DAWN_START = 23000;

    private static final int WORK_START = 0;
    private static final int HOME_START = 12000;
    private static final int REST_START = 13000;
    private static final int NIGHT_SHIFT_OFFSET = 12000;

    private NpcSchedule() {
    }

    public static boolean isDay(long dayTime) {
        long time = normalize(dayTime);
        return time >= DAWN_START || time < NIGHT_START;
    }

    public static NpcSubState subState(NpcShift shift, long dayTime) {
        long time = shiftTime(shift, dayTime);
        if (time >= DAWN_START) {
            return NpcSubState.MOVING;
        }
        if (time < HOME_START) {
            return NpcSubState.WORKING;
        }
        if (time < REST_START) {
            return NpcSubState.MOVING;
        }
        return NpcSubState.RESTING;
    }

    public static NpcDetail moveDetail(NpcShift shift, long dayTime) {
        long time = shiftTime(shift, dayTime);
        return time >= HOME_START && time < REST_START ? NpcDetail.GOING_HOME : NpcDetail.GOING_WORK;
    }

    private static long shiftTime(NpcShift shift, long dayTime) {
        int offset = shift == NpcShift.NIGHT_SHIFT ? NIGHT_SHIFT_OFFSET : 0;
        return normalize(dayTime - offset);
    }

    private static long normalize(long time) {
        long value = time % DAY_LENGTH;
        return value < 0 ? value + DAY_LENGTH : value;
    }
}
