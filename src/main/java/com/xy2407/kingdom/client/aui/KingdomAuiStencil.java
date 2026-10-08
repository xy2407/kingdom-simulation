package com.xy2407.kingdom.client.aui;

import com.xy2407.kingdom.ModMain;

/**
 * AUI stencil 抑制开关：置位期间 Mask 不走 stencil，避免主渲染目标深度被当帧重建。
 */
public final class KingdomAuiStencil {

    private static volatile boolean suppressed;
    private static volatile boolean reported;

    private KingdomAuiStencil() {
    }

    public static boolean isSuppressed() {
        if (suppressed && !reported) {
            reported = true;
            ModMain.LOGGER.info("AUI stencil 抑制已生效：Mask 退回 scissor，主目标深度不再被重建");
        }
        return suppressed;
    }

    public static void setSuppressed(boolean value) {
        suppressed = value;
    }
}
