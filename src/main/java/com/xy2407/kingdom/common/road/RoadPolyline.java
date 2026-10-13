package com.xy2407.kingdom.common.road;

import java.util.List;

/**
 * 世界道路的折线：世界坐标点集，圆头半径固定 6（D4：直径 13 格），阶段 5 提交时按它编码发包。
 */
public record RoadPolyline(float[] xs, float[] zs) {

    public static final int RADIUS = 6;

    public static RoadPolyline of(List<double[]> points) {
        int count = points.size();
        float[] xs = new float[count];
        float[] zs = new float[count];
        for (int index = 0; index < count; index++) {
            xs[index] = (float) points.get(index)[0];
            zs[index] = (float) points.get(index)[1];
        }
        return new RoadPolyline(xs, zs);
    }

    public int pointCount() {
        return xs.length;
    }
}
