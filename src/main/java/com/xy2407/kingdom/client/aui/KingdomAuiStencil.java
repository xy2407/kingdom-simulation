package com.xy2407.kingdom.client.aui;

/**
 * AUI stencil 抑制开关：置位期间 Mask 不走 stencil，避免主渲染目标深度被当帧重建。
 */
public final class KingdomAuiStencil {

    private static volatile boolean suppressed;

    private KingdomAuiStencil() {
    }

    public static boolean isSuppressed() {
        return suppressed;
    }

    public static void setSuppressed(boolean value) {
        suppressed = value;
    }
}
