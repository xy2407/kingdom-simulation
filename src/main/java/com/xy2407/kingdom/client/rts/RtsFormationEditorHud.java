package com.xy2407.kingdom.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 阵型编辑器：以队长为锚的平面网格、成员方块、世界空间操作与保存按钮
 */
@OnlyIn(Dist.CLIENT)
public final class RtsFormationEditorHud implements LayeredDraw.Layer {

    public static final RtsFormationEditorHud INSTANCE = new RtsFormationEditorHud();
    public static final String ID = "rts_formation_editor";

    public enum Kind { MEMBER, CELL, WORLD_BTN, SAVE_BTN }

    public record Area(Kind kind, UUID member, int x, int y, int w, int h) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    private static final int CELL = 8;
    private static final int HALF_CELLS = 20;
    private static final int MEMBER_SIZE = 6;
    /** 成员方块的点击半径：只比方块大一点，不能吃掉整格，否则没选中时点不到格子。 */
    private static final int MEMBER_HIT = 8;
    /** 网格线宽与配色：线要够粗、颜色要够深，压在 3D 画面上才看得清。 */
    private static final int GRID_THICKNESS = 2;
    private static final int GRID_COLOR = 0xFF14141C;
    /** 顶部两颗按钮的尺寸与相对网格的上边距（绘制与命中都用这组常量）。 */
    private static final int BUTTON_W = 92;
    private static final int BUTTON_H = 16;
    private static final int BUTTON_GAP = 26;

    private final List<Area> areas = new ArrayList<>();

    private static int centerX;
    private static int centerY;
    /** 实际生效的网格半径（格）：窗口不够高时自动收缩，保证按钮不被挤出屏幕。 */
    private static int halfCells = HALF_CELLS;

    private RtsFormationEditorHud() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!RtsModeManager.isActive() || !RtsFormationEditorState.isOpen()
                || RtsFormationEditorState.isWorldOps()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        areas.clear();
        centerX = graphics.guiWidth() / 2;
        centerY = graphics.guiHeight() / 2;
        // 预留 72px 给上方的两个按钮与提示行，窗口矮时按比例收缩网格，
        // 否则按钮会被顶到屏幕外（画得出来但点不到）。
        halfCells = Math.max(6, Math.min(HALF_CELLS, (graphics.guiHeight() - 72) / (2 * CELL)));
        int half = halfCells * CELL;
        int top = centerY - half;

        // 线条画在「格子边界」上（偏移半格）：格子中心 = centerX + i*CELL，
        // 这样网格中央的方块才是格子本身，而不是四根线的交叉点。
        int lineOffset = CELL / 2;
        for (int index = -halfCells; index <= halfCells; index++) {
            int vx = centerX + index * CELL + lineOffset;
            int hy = centerY + index * CELL + lineOffset;
            graphics.fill(vx, top, vx + GRID_THICKNESS, top + half * 2, GRID_COLOR);
            graphics.fill(centerX - half, hy, centerX + half, hy + GRID_THICKNESS, GRID_COLOR);
        }

        Vec3 anchor = RtsFormationEditorState.anchor();
        RtsTeamSyncPacket.Entry team = RtsFormationEditorState.team();
        if (anchor == null || team == null) {
            graphics.drawString(minecraft.font, "阵型同步中...", centerX - 40, centerY,
                    0xFFCCCCCC, false);
            return;
        }

        double ax = Math.floor(anchor.x) + 0.5D;
        double az = Math.floor(anchor.z) + 0.5D;
        UUID selected = RtsFormationEditorState.effectiveSelected();
        for (UUID member : team.members()) {
            Vec3 pos = RtsTeamPosCache.of(team.teamId(), member);
            if (pos == null) {
                continue;
            }
            int sx = centerX + (int) Math.round((pos.x - ax) * CELL);
            int sy = centerY + (int) Math.round((pos.z - az) * CELL);
            boolean leader = member.equals(team.leader());
            boolean chosen = member.equals(selected);
            int color = leader ? 0xFF4488FF : (chosen ? 0xFF66FF88 : 0xFF33AA55);
            graphics.fill(sx - MEMBER_SIZE / 2, sy - MEMBER_SIZE / 2,
                    sx + MEMBER_SIZE / 2, sy + MEMBER_SIZE / 2, color);
            areas.add(new Area(Kind.MEMBER, member, sx - MEMBER_HIT / 2,
                    sy - MEMBER_HIT / 2, MEMBER_HIT, MEMBER_HIT));
        }

        int buttonY = Math.max(2, top - BUTTON_GAP);
        graphics.fill(centerX - 10 - BUTTON_W, buttonY, centerX - 10, buttonY + BUTTON_H, 0xFF333355);
        graphics.drawString(minecraft.font, "世界空间操作", centerX - 10 - BUTTON_W + 5,
                buttonY + 4, 0xFFFFFFFF, false);
        areas.add(new Area(Kind.WORLD_BTN, null, centerX - 10 - BUTTON_W, buttonY,
                BUTTON_W, BUTTON_H));

        graphics.fill(centerX + 10, buttonY, centerX + 10 + BUTTON_W, buttonY + BUTTON_H, 0xFF335522);
        graphics.drawString(minecraft.font, "完成/保存阵型", centerX + 15, buttonY + 4,
                0xFFFFFFFF, false);
        areas.add(new Area(Kind.SAVE_BTN, null, centerX + 10, buttonY, BUTTON_W, BUTTON_H));

        String hint = selected == null ? "点成员选中，再点网格放置；Enter 世界空间操作"
                : "点击网格放置所选成员";
        graphics.drawString(minecraft.font, hint, centerX - 90, Math.max(2, top - 44), 0xFFFFCC66, false);
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!RtsModeManager.isActive() || !RtsFormationEditorState.isOpen()
                || RtsFormationEditorState.isWorldOps()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return false;
        }
        // 按钮命中不依赖 render 那一帧填的 areas：网格几何是纯计算，
        // 这里现算一遍，保证「画出来就一定点得到」（以前 areas 为空或还没渲染时按钮是死的）。
        syncGeometry(minecraft);
        int buttonY = Math.max(2, centerY - halfCells * CELL - BUTTON_GAP);
        if (hit(mouseX, mouseY, centerX + 10, buttonY, BUTTON_W, BUTTON_H)) {
            RtsFormationEditorState.save();
            return true;
        }
        if (hit(mouseX, mouseY, centerX - 10 - BUTTON_W, buttonY, BUTTON_W, BUTTON_H)) {
            RtsFormationEditorState.startWorldOps();
            return true;
        }
        return INSTANCE.onClick(mouseX, mouseY);
    }

    private static void syncGeometry(Minecraft minecraft) {
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        centerX = guiWidth / 2;
        centerY = guiHeight / 2;
        halfCells = Math.max(6, Math.min(HALF_CELLS, (guiHeight - 72) / (2 * CELL)));
    }

    private static boolean hit(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private boolean onClick(double mouseX, double mouseY) {
        // ① 按钮永远优先：以前按钮排在 CELL 之后、又被成员方块挡住，点了没反应
        for (Area area : areas) {
            if ((area.kind() == Kind.WORLD_BTN || area.kind() == Kind.SAVE_BTN)
                    && area.contains(mouseX, mouseY)) {
                if (area.kind() == Kind.SAVE_BTN) {
                    RtsFormationEditorState.save();
                } else {
                    RtsFormationEditorState.startWorldOps();
                }
                return true;
            }
        }
        boolean inGrid = insideGrid(mouseX, mouseY);
        // ② 已选中成员时网格优先：否则点回成员原本所在的格子只会再次"选中"它，永远放不下去
        if (inGrid && RtsFormationEditorState.effectiveSelected() != null) {
            RtsFormationEditorState.placeSelected(
                    (int) Math.round((mouseX - centerX) / CELL),
                    (int) Math.round((mouseY - centerY) / CELL));
            return true;
        }
        // ③ 没选中成员时才按成员方块来选人
        if (!inGrid) {
            for (Area area : areas) {
                if (area.kind() == Kind.MEMBER && area.contains(mouseX, mouseY)) {
                    RtsFormationEditorState.select(area.member());
                    return true;
                }
            }
            return false;
        }
        for (Area area : areas) {
            if (area.kind() == Kind.MEMBER && area.contains(mouseX, mouseY)) {
                RtsFormationEditorState.select(area.member());
                return true;
            }
        }
        // 网格内但没选中成员：吃掉这次点击，避免落到下面的框选逻辑
        return true;
    }

    public static boolean insideGrid(double mouseX, double mouseY) {
        int half = halfCells * CELL;
        return mouseX >= centerX - half && mouseX < centerX + half
                && mouseY >= centerY - half && mouseY < centerY + half;
    }

    public static float[] gridOffsetAt(double mouseX, double mouseY) {
        double dx = (mouseX - centerX) / CELL;
        double dz = (mouseY - centerY) / CELL;
        return new float[]{(float) Math.round(dx), (float) Math.round(dz)};
    }
}
