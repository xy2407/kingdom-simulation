package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.network.rts.RtsTeamCreatePacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamEditPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamEditStatePacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamOpPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;
import com.xy2407.kingdom.common.rts.RtsFormation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/** 阵型编辑器状态：当前队伍、锚点、选中成员，并把操作转成网络包发给服务端。 */
@OnlyIn(Dist.CLIENT)
public final class RtsFormationEditorState {

    private static boolean open;
    private static UUID teamId;
    private static UUID leaderUuid;
    private static UUID selectedMember;

    private RtsFormationEditorState() {
    }

    public static void open(RtsTeamSyncPacket.Entry team) {
        if (team == null) {
            return;
        }
        teamId = team.teamId();
        leaderUuid = team.leader();
        selectedMember = null;
        open = true;
        PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, true));
    }

    public static void openFirstTeam() {
        open(RtsTeamClientCache.first());
    }

    public static void close() {
        if (open && teamId != null) {
            PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, false));
        }
        open = false;
        teamId = null;
        leaderUuid = null;
        selectedMember = null;
    }

    public static boolean isOpen() {
        return open;
    }

    public static UUID teamId() {
        return teamId;
    }

    public static UUID effectiveSelected() {
        return selectedMember;
    }

    public static void select(UUID member) {
        selectedMember = member;
    }

    public static Vec3 anchor() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || leaderUuid == null) {
            return null;
        }
        Entity leader = ClientEntities.byUuid(minecraft.level, leaderUuid);
        return leader == null ? null : leader.position();
    }

    public static RtsTeamSyncPacket.Entry team() {
        return RtsTeamClientCache.byId(teamId);
    }

    public static void placeSelected(int offX, int offZ) {
        if (!open || teamId == null || selectedMember == null || selectedMember.equals(leaderUuid)) {
            return;
        }
        PacketDistributor.sendToServer(new RtsTeamEditPacket(teamId, selectedMember, offX, offZ));
    }

    public static void save() {
        if (teamId != null) {
            PacketDistributor.sendToServer(new RtsTeamOpPacket(teamId, RtsTeamOpPacket.ACTION_SAVE, 0));
        }
    }

    public static void dissolve() {
        if (teamId != null) {
            PacketDistributor.sendToServer(new RtsTeamOpPacket(teamId, RtsTeamOpPacket.ACTION_DISSOLVE, 0));
            close();
        }
    }

    public static void applyShape(RtsFormation.Shape shape) {
        if (teamId != null && shape != null) {
            PacketDistributor.sendToServer(new RtsTeamOpPacket(teamId, RtsTeamOpPacket.ACTION_SHAPE, shape.id));
        }
    }

    public static void requestCreate() {
        PacketDistributor.sendToServer(new RtsTeamCreatePacket(java.util.List.of(), 0));
    }
}
