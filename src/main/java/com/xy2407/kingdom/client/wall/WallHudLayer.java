package com.xy2407.kingdom.client.wall;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsSubMode;
import com.xy2407.kingdom.common.wall.WallKind;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 城墙模式底部按钮：城楼 / 城门 / 退出，选中部件时追加删除按钮
 */
@OnlyIn(Dist.CLIENT)
public final class WallHudLayer implements LayeredDraw.Layer {

    public static final String ID = "wall_hud";

    public static final WallHudLayer INSTANCE = new WallHudLayer();

    private static final int BTN_W = 76;

    private static final int BTN_H = 22;

    private static final int GAP = 6;

    private static final int COLOR_IDLE = 0xCC3B2C1A;

    private static final int COLOR_HOVER = 0xCC6A4E2C;

    private static final int COLOR_ON = 0xCCB8862F;

    private static final int COLOR_DELETE = 0xCCA33A2F;

    private static final int TEXT_IDLE = 0xFFF0E2C4;

    private WallHudLayer() {
    }

    private static boolean wallMode() {
        return RtsModeManager.isActive() && RtsModeManager.getSubMode() == RtsSubMode.WALL_BUILD;
    }

    /**
     * 城墙建设模式显示全部按钮；普通 RTS 模式下只要有选中件也显示删除按钮
     */
    private static boolean shown() {
        return RtsModeManager.isActive() && (wallMode() || WallClientState.hasSelection());
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!shown()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int bottom = height - 34;
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        if (!wallMode()) {
            // 普通 RTS 模式：只显示删除按钮
            if (WallClientState.hasSelection()) {
                drawButton(guiGraphics, minecraft, deleteX(width), bottom, "删除", false,
                        true, mouse);
            }
            return;
        }
        int towerX = width / 2 - BTN_W - GAP;
        int gateX = width / 2 + GAP;
        drawButton(guiGraphics, minecraft, towerX, bottom, "城楼",
                WallClientState.kind() == WallKind.TOWER, false, mouse);
        drawButton(guiGraphics, minecraft, gateX, bottom, "城门",
                WallClientState.kind() == WallKind.GATE, false, mouse);
        drawButton(guiGraphics, minecraft, width - BTN_W - 12, bottom, "退出", false, false, mouse);
        if (WallClientState.hasSelection()) {
            drawButton(guiGraphics, minecraft, deleteX(width), bottom, "删除", false,
                    true, mouse);
        }
    }

    private static int deleteX(int width) {
        return width - BTN_W * 2 - 12 - GAP;
    }

    private static void drawButton(GuiGraphics guiGraphics, Minecraft minecraft,
                                   int x, int y, String label, boolean on, boolean danger,
                                   double[] mouse) {
        boolean hover = mouse[0] >= x && mouse[0] <= x + BTN_W
                && mouse[1] >= y && mouse[1] <= y + BTN_H;
        int background = on ? COLOR_ON : (danger ? COLOR_DELETE : (hover ? COLOR_HOVER : COLOR_IDLE));
        guiGraphics.fill(x, y, x + BTN_W, y + BTN_H, background);
        guiGraphics.fill(x, y, x + BTN_W, y + 1, 0x66FFFFFF);
        guiGraphics.drawCenteredString(minecraft.font, label, x + BTN_W / 2, y + (BTN_H - 8) / 2,
                TEXT_IDLE);
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!shown()) {
            return false;
        }
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int bottom = height - 34;
        if (WallClientState.hasSelection()
                && inside(deleteX(width), bottom, mouseX, mouseY)) {
            WallClientState.deleteSelected();
            return true;
        }
        if (!wallMode()) {
            return false;
        }
        int towerX = width / 2 - BTN_W - GAP;
        int gateX = width / 2 + GAP;
        if (inside(towerX, bottom, mouseX, mouseY)) {
            WallClientState.select(WallKind.TOWER);
            return true;
        }
        if (inside(gateX, bottom, mouseX, mouseY)) {
            WallClientState.select(WallKind.GATE);
            return true;
        }
        if (inside(width - BTN_W - 12, bottom, mouseX, mouseY)) {
            RtsModeManager.exitSubMode();
            return true;
        }
        return false;
    }

    private static boolean inside(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + BTN_W && mouseY >= y && mouseY <= y + BTN_H;
    }
}
