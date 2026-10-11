package com.xy2407.kingdom.client.aui;

/**
 * AUI 屏幕文档的居中偏移计算，供客户端两个居中 mixin 共用。
 */
public final class AuiDocPlacement {

    public static final double UP_BIAS = 24.0d;
    public static final double MIN_TOP = 8.0d;

    private AuiDocPlacement() {
    }

    public static double[] centeredOffset(double guiWidth, double guiHeight, double docWidth, double docHeight) {
        double offsetX = Math.max(0.0d, (guiWidth - docWidth) / 2.0d);
        double gapY = guiHeight - docHeight;
        if (gapY <= 0.0d) {
            return new double[]{offsetX, 0.0d};
        }
        double offsetY = Math.max(Math.min(MIN_TOP, gapY), gapY / 2.0d - UP_BIAS);
        return new double[]{offsetX, Math.min(gapY, offsetY)};
    }
}
