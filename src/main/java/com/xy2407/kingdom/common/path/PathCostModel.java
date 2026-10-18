package com.xy2407.kingdom.common.path;

/**
 * 路径代价单一真相：细层 A* 与粗层路网共用同一组系数、上下限与危险惩罚。
 */
public final class PathCostModel {

    public static final double STEP_UP = 1.1D;
    public static final double STEP_DOWN = 1.2D;
    public static final double STEP_DROP = 8.0D;
    public static final int MAX_STEP_UP = 1;
    public static final int MAX_DROP = 4;
    public static final double STEP_THRESHOLD = 0.6D;
    public static final float MAX_PATH_COST = 300.0F;

    public static final float HAZARD_WATER = 4.0F;
    public static final float HAZARD_LAVA = 200.0F;
    public static final float HAZARD_FIRE = 150.0F;
    public static final float HAZARD_POWDER = 150.0F;
    public static final float HAZARD_CACTUS = 100.0F;

    public static final float ROAD_BIAS = 0.2F;

    private PathCostModel() {
    }

    public static double stepCost(double dh) {
        if (dh > STEP_THRESHOLD) {
            return 1.0D + STEP_UP * dh;
        }
        if (dh < -STEP_THRESHOLD) {
            return 1.0D + (-dh <= 1.0D ? STEP_DOWN : STEP_DROP) * Math.abs(dh);
        }
        return 1.0D;
    }

    public static double crossCost(int dy) {
        if (dy > 0) {
            return 1.0D + STEP_UP * dy;
        }
        if (dy == -1) {
            return 1.0D + STEP_DOWN;
        }
        if (dy < -1) {
            return 1.0D + STEP_DROP * (-dy);
        }
        return 1.0D;
    }

    public static float hazardCost(byte hazard, boolean cactusAdjacent) {
        float cost = switch (hazard) {
            case ChunkSnapshot.HAZARD_WATER -> HAZARD_WATER;
            case ChunkSnapshot.HAZARD_LAVA -> HAZARD_LAVA;
            case ChunkSnapshot.HAZARD_FIRE -> HAZARD_FIRE;
            case ChunkSnapshot.HAZARD_POWDER -> HAZARD_POWDER;
            default -> 0.0F;
        };
        return cactusAdjacent ? cost + HAZARD_CACTUS : cost;
    }
}
