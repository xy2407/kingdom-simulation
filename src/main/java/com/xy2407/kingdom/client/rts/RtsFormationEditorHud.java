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
    private static final int MEMBER_HIT = 16;
    private static final int GRID_COLOR = 0xFF2A2A3A;

    private final List<Area> areas = new ArrayList<>();

    private static int centerX;
    private static int centerY;

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
        int half = HALF_CELLS * CELL;
        int top = centerY - half;

        for (int index = -HALF_CELLS; index <= HALF_CELLS; index++) {
            int vx = centerX + index * CELL;
            int hy = centerY + index * CELL;
            graphics.fill(vx, top, vx + 1, top + half * 2, GRID_COLOR);
            graphics.fill(centerX - half, hy, centerX + half, hy + 1, GRID_COLOR);
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

        int buttonW = 92;
        int buttonH = 16;
        int buttonY = top - 26;
        graphics.fill(centerX - 10 - buttonW, buttonY, centerX - 10, buttonY + buttonH, 0xFF333355);
        graphics.drawString(minecraft.font, "世界空间操作", centerX - 10 - buttonW + 5,
                buttonY + 4, 0xFFFFFFFF, false);
        areas.add(new Area(Kind.WORLD_BTN, null, centerX - 10 - buttonW, buttonY,
                buttonW, buttonH));

        graphics.fill(centerX + 10, buttonY, centerX + 10 + buttonW, buttonY + buttonH, 0xFF335522);
        graphics.drawString(minecraft.font, "完成/保存阵型", centerX + 15, buttonY + 4,
                0xFFFFFFFF, false);
        areas.add(new Area(Kind.SAVE_BTN, null, centerX + 10, buttonY, buttonW, buttonH));

        String hint = selected == null ? "点成员选中，再点网格放置；Enter 世界空间操作"
                : "点击网格放置所选成员";
        graphics.drawString(minecraft.font, hint, centerX - 90, top - 44, 0xFFFFCC66, false);

        areas.add(new Area(Kind.CELL, null, centerX - half, centerY - half, half * 2, half * 2));
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!RtsFormationEditorState.isOpen() || RtsFormationEditorState.isWorldOps()) {
            return false;
        }
        return INSTANCE.onClick(mouseX, mouseY);
    }

    private boolean onClick(double mouseX, double mouseY) {
        Kind kind = null;
        UUID member = null;
        for (Area area : areas) {
            if (area.contains(mouseX, mouseY)) {
                kind = area.kind();
                member = area.member();
                break;
            }
        }
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case MEMBER -> RtsFormationEditorState.select(member);
            case WORLD_BTN -> RtsFormationEditorState.startWorldOps();
            case SAVE_BTN -> RtsFormationEditorState.save();
            case CELL -> {
                int indexX = (int) Math.round((mouseX - centerX) / CELL);
                int indexZ = (int) Math.round((mouseY - centerY) / CELL);
                RtsFormationEditorState.placeSelected(indexX, indexZ);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public static boolean insideGrid(double mouseX, double mouseY) {
        int half = HALF_CELLS * CELL;
        return mouseX >= centerX - half && mouseX < centerX + half
                && mouseY >= centerY - half && mouseY < centerY + half;
    }

    public static float[] gridOffsetAt(double mouseX, double mouseY) {
        double dx = (mouseX - centerX) / CELL;
        double dz = (mouseY - centerY) / CELL;
        return new float[]{(float) Math.round(dx), (float) Math.round(dz)};
    }
}
