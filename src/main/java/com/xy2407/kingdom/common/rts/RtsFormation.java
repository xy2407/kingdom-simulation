package com.xy2407.kingdom.common.rts;

import java.util.ArrayList;
import java.util.List;

/** RTS 阵型形状：直线/方形/三角，按人数生成相对锚点的水平偏移，间距 2 格。 */
public final class RtsFormation {

    public enum Shape {
        NONE(0), LINE(1), SQUARE(2), TRIANGLE(3);

        public final int id;

        Shape(int id) {
            this.id = id;
        }

        public static Shape fromId(int id) {
            for (Shape shape : values()) {
                if (shape.id == id) {
                    return shape;
                }
            }
            return NONE;
        }
    }

    private static final double SPACING = 2.0D;

    private RtsFormation() {
    }

    public static List<float[]> offsets(int count, Shape shape) {
        List<float[]> list = new ArrayList<>(Math.max(count, 0));
        if (count <= 0 || shape == null || shape == Shape.NONE) {
            for (int i = 0; i < Math.max(count, 0); i++) {
                list.add(new float[]{0.0F, 0.0F});
            }
            return list;
        }
        switch (shape) {
            case LINE -> line(count, list);
            case SQUARE -> square(count, list);
            case TRIANGLE -> triangle(count, list);
            default -> {
                for (int i = 0; i < count; i++) {
                    list.add(new float[]{0.0F, 0.0F});
                }
            }
        }
        return list;
    }

    private static void line(int count, List<float[]> out) {
        for (int i = 0; i < count; i++) {
            out.add(new float[]{centered(i, count), 0.0F});
        }
    }

    private static void square(int count, List<float[]> out) {
        int side = (int) Math.ceil(Math.sqrt(count));
        int filled = 0;
        for (int row = 0; row < side && filled < count; row++) {
            for (int col = 0; col < side && filled < count; col++) {
                out.add(new float[]{centered(col, side), centered(row, side)});
                filled++;
            }
        }
    }

    private static void triangle(int count, List<float[]> out) {
        int layer = 1;
        while (out.size() < count) {
            int inLayer = Math.min(layer, count - out.size());
            for (int i = 0; i < inLayer; i++) {
                out.add(new float[]{centered(i, inLayer), (float) ((layer - 1) * SPACING)});
            }
            layer++;
        }
    }

    private static float centered(int index, int total) {
        return (float) ((index - (total - 1) / 2.0D) * SPACING);
    }

    public static float[] spiral(int index) {
        if (index <= 0) {
            return new float[]{0.0F, 0.0F};
        }
        double radius = SPACING * Math.sqrt(index);
        double angle = index * 2.39996D;
        return new float[]{(float) (Math.cos(angle) * radius), (float) (Math.sin(angle) * radius)};
    }
}
