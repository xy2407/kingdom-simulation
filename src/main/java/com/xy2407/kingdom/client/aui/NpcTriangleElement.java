package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.Rect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Arrays;
import java.util.Comparator;

/**
 * 身份卡片的像素三角属性雷达图元素
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(NpcTriangleElement.TAG_NAME)
public class NpcTriangleElement extends Element {

    public static final String TAG_NAME = "NPC-TRIANGLE";

    public static final String ATTRIBUTE_POWER = "power";
    public static final String ATTRIBUTE_POWER_MAX = "power-max";
    public static final String ATTRIBUTE_WISDOM = "wisdom";
    public static final String ATTRIBUTE_WISDOM_MAX = "wisdom-max";
    public static final String ATTRIBUTE_AGILITY = "agility";
    public static final String ATTRIBUTE_AGILITY_MAX = "agility-max";

    private static final int SIZE = 96;
    private static final double CENTER_X = 48.0;
    private static final double CENTER_Y = 60.67;
    private static final double[] VERTEX_TOP = {48.0, 6.0};
    private static final double[] VERTEX_LEFT = {4.0, 88.0};
    private static final double[] VERTEX_RIGHT = {92.0, 88.0};

    private static final int COLOR_AXIS = 0x806A4A20;
    private static final int COLOR_GRID = 0xFF8A6E3C;
    private static final int COLOR_DATA_FILL = 0x808C4614;
    private static final int COLOR_DATA_EDGE = 0xFF5A2008;
    private static final int COLOR_OUTER_EDGE = 0xFF3A2814;
    private static final int COLOR_DOT_RING = 0xFF3A2814;
    private static final int COLOR_DOT_POWER = 0xFF7A3A10;
    private static final int COLOR_DOT_WISDOM = 0xFF1F4A6A;
    private static final int COLOR_DOT_AGILITY = 0xFF2F5A1C;

    private SpanSheet sheet;
    private String signature;

    public NpcTriangleElement(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                drawChart(poseStack, rect);
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void drawChart(PoseStack poseStack, Rect rect) {
        Size size = rect.getBodyRectSize();
        double side = Math.min(size.width(), size.height());
        if (side <= 0) {
            return;
        }
        Position body = rect.getBodyRectPosition();
        Position origin = new Position(
                body.x + (size.width() - side) / 2.0,
                body.y + (size.height() - side) / 2.0);
        SpanSheet.draw(poseStack, origin, new Size(side, side), sheet());
    }

    private SpanSheet sheet() {
        double power = ratio(ATTRIBUTE_POWER, ATTRIBUTE_POWER_MAX);
        double wisdom = ratio(ATTRIBUTE_WISDOM, ATTRIBUTE_WISDOM_MAX);
        double agility = ratio(ATTRIBUTE_AGILITY, ATTRIBUTE_AGILITY_MAX);
        String current = power + "|" + wisdom + "|" + agility;
        if (sheet == null || !current.equals(signature)) {
            signature = current;
            sheet = build(power, wisdom, agility);
        }
        return sheet;
    }

    private double ratio(String valueAttribute, String maxAttribute) {
        double max = number(maxAttribute);
        if (max <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, number(valueAttribute) / max));
    }

    private double number(String attribute) {
        String raw = getAttribute(attribute);
        if (raw == null || raw.isBlank()) {
            return 0.0D;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException invalid) {
            return 0.0D;
        }
    }

    private static SpanSheet build(double power, double wisdom, double agility) {
        SpanSheet sheet = new SpanSheet(SIZE, SIZE);

        line(sheet, CENTER_X, CENTER_Y, VERTEX_TOP[0], VERTEX_TOP[1], COLOR_AXIS);
        line(sheet, CENTER_X, CENTER_Y, VERTEX_LEFT[0], VERTEX_LEFT[1], COLOR_AXIS);
        line(sheet, CENTER_X, CENTER_Y, VERTEX_RIGHT[0], VERTEX_RIGHT[1], COLOR_AXIS);

        triangleOutline(sheet, shrink(VERTEX_TOP, 0.25), shrink(VERTEX_LEFT, 0.25), shrink(VERTEX_RIGHT, 0.25), COLOR_GRID, false);
        triangleOutline(sheet, shrink(VERTEX_TOP, 0.50), shrink(VERTEX_LEFT, 0.50), shrink(VERTEX_RIGHT, 0.50), COLOR_GRID, false);
        triangleOutline(sheet, shrink(VERTEX_TOP, 0.75), shrink(VERTEX_LEFT, 0.75), shrink(VERTEX_RIGHT, 0.75), COLOR_GRID, false);

        double[] dataTop = shrink(VERTEX_TOP, power);
        double[] dataLeft = shrink(VERTEX_LEFT, wisdom);
        double[] dataRight = shrink(VERTEX_RIGHT, agility);

        fillTriangle(sheet, dataTop, dataLeft, dataRight, COLOR_DATA_FILL);
        triangleOutline(sheet, dataTop, dataLeft, dataRight, COLOR_DATA_EDGE, true);
        triangleOutline(sheet, VERTEX_TOP, VERTEX_LEFT, VERTEX_RIGHT, COLOR_OUTER_EDGE, true);

        dot(sheet, dataTop[0], dataTop[1], COLOR_DOT_POWER);
        dot(sheet, dataLeft[0], dataLeft[1], COLOR_DOT_WISDOM);
        dot(sheet, dataRight[0], dataRight[1], COLOR_DOT_AGILITY);
        return sheet;
    }

    private static double[] shrink(double[] vertex, double scale) {
        return new double[]{
                CENTER_X + (vertex[0] - CENTER_X) * scale,
                CENTER_Y + (vertex[1] - CENTER_Y) * scale
        };
    }

    private static void triangleOutline(SpanSheet sheet, double[] a, double[] b, double[] c, int color, boolean thick) {
        if (thick) {
            thickLine(sheet, a[0], a[1], b[0], b[1], color);
            thickLine(sheet, b[0], b[1], c[0], c[1], color);
            thickLine(sheet, c[0], c[1], a[0], a[1], color);
            return;
        }
        line(sheet, a[0], a[1], b[0], b[1], color);
        line(sheet, b[0], b[1], c[0], c[1], color);
        line(sheet, c[0], c[1], a[0], a[1], color);
    }

    private static void thickLine(SpanSheet sheet, double x0, double y0, double x1, double y1, int color) {
        line(sheet, x0, y0, x1, y1, color);
        line(sheet, x0 + 1, y0, x1 + 1, y1, color);
        line(sheet, x0, y0 + 1, x1, y1 + 1, color);
    }

    private static void line(SpanSheet sheet, double startX, double startY, double endX, double endY, int color) {
        int x0 = (int) Math.round(startX);
        int y0 = (int) Math.round(startY);
        int x1 = (int) Math.round(endX);
        int y1 = (int) Math.round(endY);
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int stepX = x0 < x1 ? 1 : -1;
        int stepY = y0 < y1 ? 1 : -1;
        int error = dx - dy;
        while (true) {
            sheet.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int doubled = 2 * error;
            if (doubled > -dy) {
                error -= dy;
                x0 += stepX;
            }
            if (doubled < dx) {
                error += dx;
                y0 += stepY;
            }
        }
    }

    private static void fillTriangle(SpanSheet sheet, double[] a, double[] b, double[] c, int color) {
        double[][] points = {a, b, c};
        Arrays.sort(points, Comparator.comparingDouble(point -> point[1]));
        double[] first = points[0];
        double[] second = points[1];
        double[] third = points[2];

        int top = (int) Math.round(first[1]);
        int bottom = (int) Math.round(third[1]);
        for (int y = top; y <= bottom; y++) {
            double start;
            double end;
            if (y < second[1]) {
                start = interpolate(first, second, y);
                end = interpolate(first, third, y);
            } else {
                start = interpolate(second, third, y);
                end = interpolate(first, third, y);
            }
            if (start > end) {
                double swap = start;
                start = end;
                end = swap;
            }
            sheet.fill((int) Math.round(start), y, (int) Math.round(end) + 1, y + 1, color);
        }
    }

    private static double interpolate(double[] a, double[] b, double y) {
        if (b[1] == a[1]) {
            return a[0];
        }
        return a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]);
    }

    private static void dot(SpanSheet sheet, double centerX, double centerY, int color) {
        int x = (int) Math.round(centerX) - 3;
        int y = (int) Math.round(centerY) - 3;
        sheet.fill(x - 1, y - 1, x + 8, y + 8, COLOR_DOT_RING);
        sheet.fill(x, y, x + 7, y + 7, color);
    }
}
