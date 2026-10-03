package com.xy2407.kingdom.client.aui;

import com.xy2407.kingdom.ModMain;

/**
 * AUI stencil 抑制开关。
 *
 * <p>置位期间 {@code Mask} 一律不进入 stencil 分支（圆角退回轴对齐 scissor、clip-path 退回无裁剪），
 * 从而不会在渲染期调用 AUI 的 {@code enableStencil}，主渲染目标的深度附件就不会被当帧重建，
 * 逐方块渲染依赖的深度缓冲得以保持完整。</p>
 *
 * <p>只由建筑预览栏的开关驱动，避免影响其它 AUI 页面。</p>
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