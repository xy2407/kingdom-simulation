package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;
import com.xy2407.kingdom.common.rts.RtsFormation;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 阵型编辑器 HUD（原版 GuiGraphics）：以队长为原点的网格、成员方块与操作按钮。 */
@OnlyIn(Dist.CLIENT)
public final class RtsFormationEditorHud implements LayeredDraw.Layer {

    public static final RtsFormationEditorHud INSTANCE = new RtsFormationEditorHud();
    public static final String ID = "rts_formation_editor";

    public enum Kind { MEMBER, CELL, SAVE, DISSOLVE, CLOSE, SHAPE_LINE, SHAPE_SQUARE, SHAPE_TRIANGLE }

    private record Area(Kind kind, UUID member, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int CELL = 8;
    private static final int HALF_CELLS = 20;
    private static final int MEMBER_SIZE = 6;
    private static final int GRID_COLOR = 0xFF2A2A3A;

    private final List<Area> areas = new ArrayList<>();

    private RtsFormationEditorHud() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!RtsFormationEditorState.isOpen()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.level == null) {
            return;
        }
        areas.clear();
        int centerX = guiGraphics.guiWidth() / 2;
        int centerY = guiGraphics.guiHeight() / 2;
        int half = HALF_CELLS * CELL;

        for (int i = -HALF_CELLS; i <= HALF_CELLS; i++) {
            int vx = centerX + i * CELL;
            int hy = centerY + i * CELL;
            guiGraphics.fill(vx, centerY - half, vx + 1, centerY + half, GRID_COLOR);
            guiGraphics.fill(centerX - half, hy, centerX + half, hy + 1, GRID_COLOR);
        }

        Vec3 anchor = RtsFormationEditorState.anchor();
        RtsTeamSyncPacket.Entry team = RtsFormationEditorState.team();
        if (anchor == null || team == null) {
            guiGraphics.drawString(minecraft.font, "阵型同步中...", centerX - 40, centerY, 0xFFCCCCCC, false);
            return;
        }

        double ax = Math.floor(anchor.x) + 0.5D;
        double az = Math.floor(anchor.z) + 0.5D;
        UUID selected = RtsFormationEditorState.effectiveSelected();
        for (UUID member : team.members()) {
            Entity entity = ClientEntities.byUuid(minecraft.level, member);
            if (entity == null) {
                continue;
            }
            int sx = centerX + (int) Math.round((entity.getX() - ax) * CELL);
            int sy = centerY + (int) Math.round((entity.getZ() - az) * CELL);
            boolean leader = member.equals(team.leader());
            boolean isSelected = member.equals(selected);
            int color = leader ? 0xFF4488FF : (isSelected ? 0xFF66FF88 : 0xFF33AA55);
            guiGraphics.fill(sx - MEMBER_SIZE / 2, sy - MEMBER_SIZE / 2,
                    sx + MEMBER_SIZE / 2, sy + MEMBER_SIZE / 2, color);
            areas.add(new Area(Kind.MEMBER, member, sx - MEMBER_SIZE / 2, sy - MEMBER_SIZE / 2,
                    MEMBER_SIZE, MEMBER_SIZE));
        }

        int btnW = 76;
        int btnH = 16;
        int btnY = centerY - half - 24;
        int x = centerX - ((btnW + 4) * 6) / 2;
        x = button(guiGraphics, minecraft, x, btnY, btnW, btnH, "直线", 0xFF333355, Kind.SHAPE_LINE);
        x = button(guiGraphics, minecraft, x, btnY, btnW, btnH, "方阵", 0xFF333355, Kind.SHAPE_SQUARE);
        x = button(guiGraphics, minecraft, x, btnY, btnW, btnH, "三角", 0xFF333355, Kind.SHAPE_TRIANGLE);
        x = button(guiGraphics, minecraft, x, btnY, btnW, btnH, "保存阵型", 0xFF335522, Kind.SAVE);
        x = button(guiGraphics, minecraft, x, btnY, btnW, btnH, "解散", 0xFF553322, Kind.DISSOLVE);
        button(guiGraphics, minecraft, x, btnY, btnW, btnH, "关闭", 0xFF333333, Kind.CLOSE);

        if (selected != null && !selected.equals(team.leader())) {
            guiGraphics.drawString(minecraft.font, "点击网格放置该成员",
                    centerX - 60, centerY + half + 8, 0xFFFFCC66, false);
        }

        areas.add(new Area(Kind.CELL, null, centerX - half, centerY - half, half * 2, half * 2));
    }

    private int button(GuiGraphics guiGraphics, Minecraft minecraft, int x, int y, int w, int h,
                       String label, int color, Kind kind) {
        guiGraphics.fill(x, y, x + w, y + h, color);
        guiGraphics.drawString(minecraft.font, label, x + 4, y + 4, 0xFFFFFF, false);
        areas.add(new Area(kind, null, x, y, w, h));
        return x + w + 4;
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!RtsFormationEditorState.isOpen()) {
            return false;
        }
        return INSTANCE.onClick(mouseX, mouseY);
    }

    private boolean onClick(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        Kind kind = null;
        UUID member = null;
        for (Area area : areas) {
            if (area.contains(mouseX, mouseY)) {
                kind = area.kind();
                member = area.member();
            }
        }
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case MEMBER -> RtsFormationEditorState.select(member);
            case SAVE -> RtsFormationEditorState.save();
            case DISSOLVE -> RtsFormationEditorState.dissolve();
            case CLOSE -> RtsFormationEditorState.close();
            case SHAPE_LINE -> RtsFormationEditorState.applyShape(RtsFormation.Shape.LINE);
            case SHAPE_SQUARE -> RtsFormationEditorState.applyShape(RtsFormation.Shape.SQUARE);
            case SHAPE_TRIANGLE -> RtsFormationEditorState.applyShape(RtsFormation.Shape.TRIANGLE);
            case CELL -> {
                int centerX = minecraft.getWindow().getGuiScaledWidth() / 2;
                int centerY = minecraft.getWindow().getGuiScaledHeight() / 2;
                int offX = (int) Math.round((mouseX - centerX) / CELL);
                int offZ = (int) Math.round((mouseY - centerY) / CELL);
                RtsFormationEditorState.placeSelected(offX, offZ);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
