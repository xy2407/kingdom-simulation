package com.xy2407.kingdom.client.rts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * RTS 的屏幕叠加绘制，包含框选矩形、状态提示与跟随鼠标的自定义准星
 */
@OnlyIn(Dist.CLIENT)
public final class RtsSelectionRenderer {

    private static final int BOX_BORDER = 0xFFE8D9A8;
    private static final int BOX_FILL = 0x40203040;
    private static final int ATTACK_BOX_BORDER = 0xFFFF5544;
    private static final int ATTACK_BOX_FILL = 0x40A01818;
    private static final int CROSSHAIR_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFE8D9A8;

    private RtsSelectionRenderer() {
    }

    public static void render(GuiGraphics graphics) {
        drawSelectionBox(graphics);
        drawStatus(graphics);
        drawCrosshair(graphics);
    }

    private static void drawCrosshair(GuiGraphics graphics) {
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        int x = (int) mouse[0];
        int y = (int) mouse[1];
        graphics.fill(x - 1, y - 1, x + 2, y + 2, CROSSHAIR_COLOR);
        graphics.fill(x - 8, y, x - 2, y + 1, CROSSHAIR_COLOR);
        graphics.fill(x + 3, y, x + 9, y + 1, CROSSHAIR_COLOR);
        graphics.fill(x, y - 8, x + 1, y - 2, CROSSHAIR_COLOR);
        graphics.fill(x, y + 3, x + 1, y + 9, CROSSHAIR_COLOR);
    }

    private static void drawSelectionBox(GuiGraphics graphics) {
        if (!RtsModeManager.isBoxSelecting()) {
            return;
        }
        int x1 = (int) Math.round(RtsModeManager.getBoxStartX());
        int y1 = (int) Math.round(RtsModeManager.getBoxStartY());
        int x2 = (int) Math.round(RtsModeManager.getBoxEndX());
        int y2 = (int) Math.round(RtsModeManager.getBoxEndY());
        int left = Math.min(x1, x2);
        int top = Math.min(y1, y2);
        int right = Math.max(x1, x2);
        int bottom = Math.max(y1, y2);
        boolean attack = RtsModeManager.isAttackBoxSelecting();
        int fill = attack ? ATTACK_BOX_FILL : BOX_FILL;
        int border = attack ? ATTACK_BOX_BORDER : BOX_BORDER;
        graphics.fill(left, top, right, bottom, fill);
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top, left + 1, bottom, border);
        graphics.fill(right - 1, top, right, bottom, border);
    }

    private static void drawStatus(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String mode = RtsModeManager.isOrthoEnabled() ? "正交" : "透视";
        String view = switch (RtsModeManager.getViewMode()) {
            case FREE -> "自由";
            case ISO_60 -> "等轴60";
            case ISO_45 -> "等轴45";
        };
        String line = "RTS · " + view + " · " + mode
                + " · 已选 " + RtsModeManager.getSelectedEntities().size()
                + (RtsModeManager.isSelectionLocked() ? " · 已锁定" : "");
        graphics.drawString(minecraft.font, line, 6, 6, TEXT_COLOR, true);
        if (RtsWaypointState.isActive()) {
            graphics.drawString(minecraft.font,
                    "节点寻路：已录 " + RtsWaypointState.size() + " 个点（右键追加，松开 C 提交）",
                    6, 18, TEXT_COLOR, true);
        }
        if (RtsPatrolState.isArmed()) {
            String hint = RtsPatrolState.isRecording()
                    ? "队伍巡逻：已录 " + RtsPatrolState.size() + " 个节点（右键追加，松开 V 提交）"
                    : "队伍巡逻：按住 V 依次右键点选起点 → 终点，松开 V 提交";
            graphics.drawString(minecraft.font, hint, 6, 30, 0xFFE8C040, true);
        }
    }
}
