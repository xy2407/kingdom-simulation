package com.xy2407.kingdom.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 队伍面板渲染：选中多人时按领导建队，否则按队伍卡片管理，支持拖动与滚动
 */
@OnlyIn(Dist.CLIENT)
public final class RtsTeamPanelRenderer implements LayeredDraw.Layer {

    public static final RtsTeamPanelRenderer INSTANCE = new RtsTeamPanelRenderer();
    public static final String ID = "rts_team_panel";

    public enum Kind { LEADER, CREATE, FOCUS, EDIT, DELETE, JOIN, LEAVE, TITLE }

    public record Area(Kind kind, int index, int x, int y, int w, int h) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 300;
    private static final int TITLE_H = 22;
    private static final int HEAD = 18;

    private static final int BG = 0xCC101020;
    private static final int BORDER = 0xFF555588;
    private static final int TITLE_BG = 0xFF1A1A2E;
    private static final int CARD = 0xFF1A1A2E;
    private static final int CARD_MINE = 0xFF224455;
    private static final int BUTTON = 0xFF333355;
    private static final int BUTTON_MAIN = 0xFF335522;
    private static final int BUTTON_DANGER = 0xFF552222;
    private static final int TEXT = 0xFFCCCCCC;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int TEXT_TITLE = 0xFFFFAA00;

    private final List<Area> areas = new ArrayList<>();

    private RtsTeamPanelRenderer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!RtsTeamPanelState.isOpen()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        RtsTeamPanelState.recompute();
        if (RtsTeamPanelState.mode() == RtsTeamPanelState.Mode.NONE) {
            RtsTeamPanelState.close();
            return;
        }
        areas.clear();
        int px = (int) RtsTeamPanelState.panelX();
        int py = (int) RtsTeamPanelState.panelY();
        graphics.fill(px, py, px + PANEL_W, py + PANEL_H, BG);
        outline(graphics, px, py, PANEL_W, PANEL_H, BORDER);
        graphics.fill(px + 1, py + 1, px + PANEL_W - 1, py + TITLE_H, TITLE_BG);
        graphics.drawString(minecraft.font, "队 伍 管 理", px + 8, py + 7, TEXT_TITLE, false);
        graphics.drawString(minecraft.font, "G 关闭", px + PANEL_W - 46, py + 7, TEXT_DIM, false);
        areas.add(new Area(Kind.TITLE, -1, px, py, PANEL_W, TITLE_H));

        if (RtsTeamPanelState.mode() == RtsTeamPanelState.Mode.PICK_LEADER) {
            renderPickLeader(graphics, minecraft, px, py);
        } else {
            renderTeamList(graphics, minecraft, px, py);
        }
    }

    private void renderPickLeader(GuiGraphics graphics, Minecraft minecraft, int px, int py) {
        graphics.drawString(minecraft.font,
                "创建队伍：先点一名领导（选中 " + RtsModeManager.getSelectedEntities().size() + " 人）",
                px + 8, py + TITLE_H + 6, TEXT, false);
        button(graphics, minecraft, px + 8, py + TITLE_H + 20, 88, 16, "创建队伍",
                BUTTON_MAIN, Kind.CREATE, 0);

        List<UUID> picked = new ArrayList<>(RtsModeManager.getSelectedEntities());
        int startY = py + TITLE_H + 42;
        int step = 24;
        int limitY = py + PANEL_H - 8;
        int visible = Math.max(1, (limitY - startY) / step);
        int offset = Math.min(RtsTeamPanelState.scrollOffset(), Math.max(0, picked.size() - visible));
        for (int index = offset; index < picked.size(); index++) {
            int y = startY + (index - offset) * step;
            if (y + step > limitY) {
                break;
            }
            UUID id = picked.get(index);
            boolean chosen = id.equals(RtsTeamPanelState.selectedLeader());
            graphics.fill(px + 4, y, px + PANEL_W - 4, y + step - 2, chosen ? CARD_MINE : CARD);
            NpcHeadRenderer.render(graphics, RtsTeamRosterCache.skinOf(id), px + 8, y + 2, HEAD);
            graphics.drawString(minecraft.font, trim(RtsTeamRosterCache.nameOf(id), 10),
                    px + 8 + HEAD + 4, y + 5, chosen ? 0xFFFFFFFF : TEXT, false);
            button(graphics, minecraft, px + PANEL_W - 80, y + 3, 72, 14,
                    chosen ? "已选领导" : "设为领导", BUTTON, Kind.LEADER, index);
        }
        RtsTeamPanelState.setScrollOffset(offset);
    }

    private void renderTeamList(GuiGraphics graphics, Minecraft minecraft, int px, int py) {
        UUID single = RtsTeamPanelState.selectedSingle();
        List<RtsTeamSyncPacket.Entry> teams = orderedTeams(single);
        graphics.drawString(minecraft.font,
                single == null ? "我的队伍（管理）" : "我的队伍（已选 1 人，先退出再加入）",
                px + 8, py + TITLE_H + 6, TEXT, false);
        if (teams.isEmpty()) {
            graphics.drawString(minecraft.font, "还没有队伍：框选 2 人以上再按 G",
                    px + 8, py + TITLE_H + 26, TEXT_DIM, false);
            return;
        }
        int startY = py + TITLE_H + 20;
        int step = 46;
        int limitY = py + PANEL_H - 8;
        int visible = Math.max(1, (limitY - startY) / step);
        int offset = Math.min(RtsTeamPanelState.scrollOffset(), Math.max(0, teams.size() - visible));
        for (int index = offset; index < teams.size(); index++) {
            int y = startY + (index - offset) * step;
            if (y + step > limitY) {
                break;
            }
            RtsTeamSyncPacket.Entry team = teams.get(index);
            boolean mine = single != null && team.members().contains(single);
            graphics.fill(px + 4, y, px + PANEL_W - 4, y + step - 4, mine ? CARD_MINE : CARD);

            int shown = Math.min(4, team.members().size());
            for (int avatar = 0; avatar < shown; avatar++) {
                NpcHeadRenderer.render(graphics,
                        RtsTeamRosterCache.skinOf(team.members().get(avatar)),
                        px + 8 + avatar * (HEAD + 2), y + 3, HEAD - 2);
            }
            String names = memberNames(team, 3);
            graphics.drawString(minecraft.font, trim(names, 14),
                    px + 8 + shown * (HEAD + 2) + 4, y + 3, TEXT, false);
            String meta = team.members().size() + " 人" + (team.saved() ? " · 阵型已存" : "");
            graphics.drawString(minecraft.font, meta,
                    px + 8 + shown * (HEAD + 2) + 4, y + 14, TEXT_DIM, false);

            int buttonY = y + 26;
            if (single != null) {
                button(graphics, minecraft, px + 8, buttonY, 60, 14,
                        mine ? "退出" : "加入", mine ? BUTTON_DANGER : BUTTON_MAIN,
                        mine ? Kind.LEAVE : Kind.JOIN, index);
            } else {
                int x = px + 8;
                x = button(graphics, minecraft, x, buttonY, 84, 14, "选中/前往",
                        BUTTON, Kind.FOCUS, index);
                x = button(graphics, minecraft, x, buttonY, 76, 14, "设计阵型",
                        BUTTON, Kind.EDIT, index);
                button(graphics, minecraft, x, buttonY, 60, 14, "删除",
                        BUTTON_DANGER, Kind.DELETE, index);
            }
        }
        RtsTeamPanelState.setScrollOffset(offset);
    }

    private int button(GuiGraphics graphics, Minecraft minecraft, int x, int y, int width,
                       int height, String label, int color, Kind kind, int index) {
        graphics.fill(x, y, x + width, y + height, color);
        graphics.drawString(minecraft.font, label, x + 5, y + 3, 0xFFFFFFFF, false);
        areas.add(new Area(kind, index, x, y, width, height));
        return x + width + 4;
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    public static List<RtsTeamSyncPacket.Entry> orderedTeams(UUID single) {
        List<RtsTeamSyncPacket.Entry> all = RtsTeamClientCache.teams();
        List<RtsTeamSyncPacket.Entry> result = new ArrayList<>(all.size());
        RtsTeamSyncPacket.Entry mine = single == null ? null : RtsTeamClientCache.byMember(single);
        if (mine != null) {
            result.add(mine);
        }
        for (RtsTeamSyncPacket.Entry entry : all) {
            if (entry != mine) {
                result.add(entry);
            }
        }
        return result;
    }

    public static Area hitTest(double mouseX, double mouseY) {
        Area found = null;
        for (Area area : INSTANCE.areas) {
            if (area.contains(mouseX, mouseY)) {
                found = area;
            }
        }
        return found;
    }

    public static boolean isMouseInPanel(double mouseX, double mouseY) {
        if (!RtsTeamPanelState.isOpen()) {
            return false;
        }
        float px = RtsTeamPanelState.panelX();
        float py = RtsTeamPanelState.panelY();
        return mouseX >= px && mouseX <= px + PANEL_W && mouseY >= py && mouseY <= py + PANEL_H;
    }

    public static boolean isInTitleBar(double mouseX, double mouseY) {
        if (!isMouseInPanel(mouseX, mouseY)) {
            return false;
        }
        float py = RtsTeamPanelState.panelY();
        return mouseY >= py && mouseY <= py + TITLE_H;
    }

    private static String memberNames(RtsTeamSyncPacket.Entry team, int limit) {
        StringBuilder builder = new StringBuilder();
        int count = Math.min(limit, team.members().size());
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                builder.append('、');
            }
            builder.append(RtsTeamRosterCache.nameOf(team.members().get(index)));
        }
        if (team.members().size() > limit) {
            builder.append(" 等").append(team.members().size()).append("人");
        }
        return builder.toString();
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
