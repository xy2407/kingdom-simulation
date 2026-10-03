package com.xy2407.kingdom.client.aui;

/**
 * AUI 屏幕文档的居中偏移计算，供两个居中 mixin 共用
 */
public final class AuiDocPlacement {

    /** 垂直居中后整体上移的量（GUI 像素）。 */
    public static final double UP_BIAS = 24.0d;
    /** 文档顶边距窗口顶的最小距离，保证"上移但不贴顶"。 */
    public static final double MIN_TOP = 8.0d;

    private AuiDocPlacement() {
    }

    /** 水平居中；垂直居中后上移 UP_BIAS，且顶边距不小于 MIN_TOP。 */
    public static double[] centeredOffset(double guiWidth, double guiHeight, double docWidth, double docHeight) {
        double offsetX = Math.max(0.0d, (guiWidth - docWidth) / 2.0d);
        double offsetY = Math.max(0.0d, (guiHeight - docHeight) / 2.0d);
        offsetY = Math.max(MIN_TOP, offsetY - UP_BIAS);
        return new double[]{offsetX, offsetY};
    }
}
