package com.xy2407.kingdom.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.network.rts.RtsTeamCreatePacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamDeletePacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamFocusPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamJoinPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamLeavePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 队伍面板状态：选中多于一人时进入选领导建队模式，否则进入多队伍管理列表
 */
@OnlyIn(Dist.CLIENT)
public final class RtsTeamPanelState {

    public enum Mode { NONE, PICK_LEADER, TEAM_LIST }

    private static boolean open;
    private static Mode mode = Mode.NONE;
    private static UUID selectedLeader;
    private static float panelX = 16.0F;
    private static float panelY = 16.0F;
    private static int scrollOffset;
    private static boolean dragging;
    private static float dragOffsetX;
    private static float dragOffsetY;

    private RtsTeamPanelState() {
    }

    public static void recompute() {
        int selected = RtsModeManager.getSelectedEntities().size();
        if (selected >= 2) {
            mode = Mode.PICK_LEADER;
        } else {
            mode = RtsTeamClientCache.teams().isEmpty() ? Mode.NONE : Mode.TEAM_LIST;
        }
    }

    public static UUID selectedSingle() {
        if (RtsModeManager.getSelectedEntities().size() != 1) {
            return null;
        }
        return RtsModeManager.getSelectedEntities().iterator().next();
    }

    public static void toggle() {
        if (open) {
            close();
            return;
        }
        open = true;
        recompute();
        if (mode == Mode.NONE) {
            open = false;
            hint("先框选至少 2 名市民，或先创建一支队伍");
        }
    }

    public static void close() {
        open = false;
        mode = Mode.NONE;
        selectedLeader = null;
        scrollOffset = 0;
        dragging = false;
    }

    public static boolean isOpen() {
        return open;
    }

    public static Mode mode() {
        return mode;
    }

    public static UUID selectedLeader() {
        return selectedLeader;
    }

    public static void selectLeader(UUID leader) {
        selectedLeader = leader;
    }

    public static float panelX() {
        return panelX;
    }

    public static float panelY() {
        return panelY;
    }

    public static int scrollOffset() {
        return scrollOffset;
    }

    public static void setScrollOffset(int offset) {
        scrollOffset = Math.max(0, offset);
    }

    public static void startDrag(double mouseX, double mouseY) {
        dragging = true;
        dragOffsetX = (float) (mouseX - panelX);
        dragOffsetY = (float) (mouseY - panelY);
    }

    public static void updateDrag(double mouseX, double mouseY) {
        if (!dragging) {
            return;
        }
        panelX = (float) mouseX - dragOffsetX;
        panelY = (float) mouseY - dragOffsetY;
    }

    public static void endDrag() {
        dragging = false;
    }

    public static boolean dragging() {
        return dragging;
    }

    public static void createTeam() {
        if (selectedLeader == null) {
            hint("请先在列表里点一名市民设为领导");
            return;
        }
        List<UUID> memberIds = new ArrayList<>();
        memberIds.add(selectedLeader);
        for (UUID id : RtsModeManager.getSelectedEntities()) {
            if (!id.equals(selectedLeader)) {
                memberIds.add(id);
            }
        }
        if (memberIds.size() < 2) {
            hint("至少需要 2 名市民才能建队");
            return;
        }
        PacketDistributor.sendToServer(new RtsTeamCreatePacket(memberIds, 0));
        hint("已提交建队请求");
    }

    public static void deleteTeam(UUID teamId) {
        PacketDistributor.sendToServer(new RtsTeamDeletePacket(teamId));
    }

    public static void focusLeader(UUID teamId) {
        PacketDistributor.sendToServer(new RtsTeamFocusPacket(teamId));
    }

    public static void joinTeam(UUID teamId, UUID memberUuid) {
        PacketDistributor.sendToServer(new RtsTeamJoinPacket(teamId, memberUuid));
    }

    public static void leaveTeam(UUID teamId, UUID memberUuid) {
        PacketDistributor.sendToServer(new RtsTeamLeavePacket(teamId, memberUuid));
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }
}
