package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.common.network.rts.RtsTeamEditPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamEditStatePacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamOpPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 阵型编辑器状态：当前队伍、队长锚点、选中成员与世界空间模式开关
 */
@OnlyIn(Dist.CLIENT)
public final class RtsFormationEditorState {

    private static boolean open;
    private static boolean worldOps;
    private static UUID teamId;
    private static UUID leaderUuid;
    private static UUID selectedMember;
    private static Vec3 anchor;

    private RtsFormationEditorState() {
    }

    public static void open(RtsTeamSyncPacket.Entry team) {
        if (team == null) {
            return;
        }
        teamId = team.teamId();
        leaderUuid = team.leader();
        selectedMember = null;
        worldOps = false;
        anchor = RtsTeamPosCache.of(team.teamId(), team.leader());
        open = true;
        PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, true));
    }

    public static void openFirstTeam() {
        open(RtsTeamClientCache.first());
    }

    public static void openById(UUID id) {
        RtsTeamSyncPacket.Entry entry = RtsTeamClientCache.byId(id);
        if (entry != null) {
            open(entry);
        }
    }

    public static void close() {
        if (open && teamId != null
                && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, false));
        }
        open = false;
        worldOps = false;
        teamId = null;
        leaderUuid = null;
        selectedMember = null;
        anchor = null;
    }

    public static boolean isOpen() {
        return open;
    }

    public static boolean isWorldOps() {
        return worldOps;
    }

    public static UUID teamId() {
        return teamId;
    }

    public static UUID leaderUuid() {
        return leaderUuid;
    }

    public static UUID effectiveSelected() {
        return selectedMember;
    }

    public static void select(UUID member) {
        selectedMember = member;
    }

    public static Vec3 anchor() {
        RtsTeamSyncPacket.Entry entry = team();
        if (entry != null) {
            Vec3 cached = RtsTeamPosCache.of(entry.teamId(), entry.leader());
            if (cached != null) {
                anchor = cached;
            }
        }
        return anchor;
    }

    public static RtsTeamSyncPacket.Entry team() {
        return RtsTeamClientCache.byId(teamId);
    }

    public static void startWorldOps() {
        worldOps = true;
        selectedMember = null;
        if (teamId != null) {
            PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, true, true));
        }
    }

    public static void returnToGrid() {
        worldOps = false;
        if (teamId != null) {
            PacketDistributor.sendToServer(new RtsTeamEditStatePacket(teamId, true, false));
        }
        RtsTeamSyncPacket.Entry entry = team();
        if (entry != null) {
            anchor = RtsTeamPosCache.of(entry.teamId(), entry.leader());
        }
    }

    public static void placeSelected(int offX, int offZ) {
        if (!open || teamId == null || selectedMember == null || anchor == null
                || selectedMember.equals(leaderUuid)) {
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
            PacketDistributor.sendToServer(new RtsTeamOpPacket(teamId,
                    RtsTeamOpPacket.ACTION_DISSOLVE, 0));
            close();
        }
    }

    public static void applyShape(com.xy2407.kingdom.common.rts.RtsFormation.Shape shape) {
        if (teamId != null && shape != null) {
            PacketDistributor.sendToServer(new RtsTeamOpPacket(teamId,
                    RtsTeamOpPacket.ACTION_SHAPE, shape.id));
        }
    }

    public static void requestCreate() {
        PacketDistributor.sendToServer(
                new com.xy2407.kingdom.common.network.rts.RtsTeamCreatePacket(java.util.List.of(), 0));
    }
}
