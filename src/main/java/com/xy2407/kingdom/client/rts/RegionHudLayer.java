package com.xy2407.kingdom.client.rts;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 区域任务的 HUD：底部按钮行与创建确认框，用原版矩形与文字自绘
 */
@OnlyIn(Dist.CLIENT)
public final class RegionHudLayer implements LayeredDraw.Layer {

    public static final RegionHudLayer INSTANCE = new RegionHudLayer();
    public static final String ID = "rts_region_hud";

    private static final String[] LABELS = {"退出", "重置(R)"};
    private static final int BTN_W = 56;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 4;
    private static final int BOTTOM_MARGIN = 24;

    private static final int COLOR_IDLE = 0xFF4A3218;
    private static final int COLOR_HOVER = 0xFF8A6E3C;
    private static final int COLOR_ON = 0xFF6A4A20;
    private static final int COLOR_TOP = 0xFFD8BC8C;
    private static final int COLOR_BOTTOM = 0xFF2A1A08;
    private static final int TEXT_IDLE = 0xFFF0E0C0;
    private static final int TEXT_ON = 0xFFF5E08A;

    private static final int CONFIRM_W = 260;
    private static final int CONFIRM_H = 96;

    private static boolean confirming;
    private static RegionSelectionState.Kind confirmKind;
    private static long confirmVolume;

    private RegionHudLayer() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!isShown()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int left = leftX();
        int top = topY();
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        for (int index = 0; index < LABELS.length; index++) {
            int x = left + index * (BTN_W + BTN_GAP);
            boolean hover = inside(x, top, mouse[0], mouse[1]);
            guiGraphics.fill(x, top, x + BTN_W, top + BTN_H, hover ? COLOR_HOVER : COLOR_IDLE);
            guiGraphics.fill(x, top, x + BTN_W, top + 1, COLOR_TOP);
            guiGraphics.fill(x, top + BTN_H - 1, x + BTN_W, top + BTN_H, COLOR_BOTTOM);
            guiGraphics.drawCenteredString(minecraft.font, LABELS[index],
                    x + BTN_W / 2, top + (BTN_H - 8) / 2, TEXT_IDLE);
        }
        int modeX = left + LABELS.length * (BTN_W + BTN_GAP);
        boolean fill = RegionSelectionState.kind() == RegionSelectionState.Kind.FILL;
        guiGraphics.fill(modeX, top, modeX + BTN_W + 12, top + BTN_H, COLOR_ON);
        guiGraphics.fill(modeX, top, modeX + BTN_W + 12, top + 1, COLOR_TOP);
        guiGraphics.fill(modeX, top + BTN_H - 1, modeX + BTN_W + 12, top + BTN_H, COLOR_BOTTOM);
        guiGraphics.drawCenteredString(minecraft.font, fill ? "填充" : "拆除",
                modeX + (BTN_W + 12) / 2, top + (BTN_H - 8) / 2, fill ? 0xFFB8E08A : 0xFFFF6A5A);
        if (confirming) {
            renderConfirm(guiGraphics, minecraft);
        }
    }

    private static void renderConfirm(GuiGraphics guiGraphics, Minecraft minecraft) {
        int[] panel = confirmPanel();
        int x = panel[0];
        int y = panel[1];
        int w = panel[2];
        int h = panel[3];
        boolean fill = confirmKind == RegionSelectionState.Kind.FILL;
        guiGraphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, COLOR_BOTTOM);
        guiGraphics.fill(x, y, x + w, y + h, 0xFFC8AC7C);
        guiGraphics.fill(x, y, x + w, y + 2, COLOR_TOP);
        guiGraphics.drawCenteredString(minecraft.font, fill ? "确认创建填充任务？" : "确认创建拆除任务？",
                x + w / 2, y + 12, 0xFF1A0E04);
        guiGraphics.drawCenteredString(minecraft.font, "范围共 " + confirmVolume + " 格，将进入建筑工看板",
                x + w / 2, y + 30, 0xFF4A3218);
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        drawButton(guiGraphics, minecraft, cancelRect(panel), "取消", mouse);
        drawButton(guiGraphics, minecraft, submitRect(panel), "确认", mouse);
    }

    private static void drawButton(GuiGraphics guiGraphics, Minecraft minecraft,
                                   int[] rect, String label, double[] mouse) {
        boolean hover = insideRect(rect, mouse[0], mouse[1]);
        guiGraphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                hover ? COLOR_HOVER : COLOR_IDLE);
        guiGraphics.drawCenteredString(minecraft.font, label,
                rect[0] + rect[2] / 2, rect[1] + (rect[3] - 8) / 2, TEXT_IDLE);
    }

    public static void openConfirm(RegionSelectionState.Kind kind, long volume) {
        confirming = true;
        confirmKind = kind;
        confirmVolume = volume;
    }

    public static void closeConfirm() {
        confirming = false;
    }

    public static boolean isConfirming() {
        return confirming;
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!isShown()) {
            return false;
        }
        if (confirming) {
            int[] panel = confirmPanel();
            if (insideRect(cancelRect(panel), mouseX, mouseY)) {
                closeConfirm();
                return true;
            }
            if (insideRect(submitRect(panel), mouseX, mouseY)) {
                closeConfirm();
                RegionSelectionState.confirm();
                return true;
            }
            return true;
        }
        int index = hitIndex(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        if (index == 0) {
            RtsModeManager.exitSubMode();
        } else {
            RegionSelectionState.resetCorners();
        }
        return true;
    }

    private static int hitIndex(double mouseX, double mouseY) {
        int top = topY();
        if (mouseY < top || mouseY > top + BTN_H) {
            return -1;
        }
        int left = leftX();
        for (int index = 0; index < LABELS.length; index++) {
            int x = left + index * (BTN_W + BTN_GAP);
            if (mouseX >= x && mouseX <= x + BTN_W) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isShown() {
        return RtsModeManager.isActive()
                && RtsModeManager.getSubMode() == RtsSubMode.REGION_TASK
                && Minecraft.getInstance().screen == null;
    }

    private static int leftX() {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int total = LABELS.length * BTN_W + (LABELS.length - 1) * BTN_GAP;
        return (width - total) / 2;
    }

    private static int topY() {
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return height - BOTTOM_MARGIN - BTN_H;
    }

    private static int[] confirmPanel() {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return new int[] {(width - CONFIRM_W) / 2, (height - CONFIRM_H) / 2, CONFIRM_W, CONFIRM_H};
    }

    private static int[] cancelRect(int[] panel) {
        return new int[] {panel[0] + 16, panel[1] + panel[3] - 28, 84, 20};
    }

    private static int[] submitRect(int[] panel) {
        return new int[] {panel[0] + panel[2] - 100, panel[1] + panel[3] - 28, 84, 20};
    }

    private static boolean insideRect(int[] rect, double mouseX, double mouseY) {
        return mouseX >= rect[0] && mouseX <= rect[0] + rect[2]
                && mouseY >= rect[1] && mouseY <= rect[1] + rect[3];
    }

    private static boolean inside(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + BTN_W && mouseY >= y && mouseY <= y + BTN_H;
    }
}
