package com.xy2407.kingdom.server.rts;

import com.xy2407.kingdom.common.rts.RtsFormation;
import com.xy2407.kingdom.common.rts.RtsTeam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** RTS 队伍表：建队/解散/增删成员/阵型偏移与保存标记，全部按玩家归属隔离。 */
public final class RtsTeamManager {

    private static final Map<UUID, RtsTeam> TEAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> MEMBER_TEAM = new ConcurrentHashMap<>();

    private RtsTeamManager() {
    }

    public static RtsTeam create(UUID owner, UUID leader, List<UUID> members) {
        if (owner == null || leader == null) {
            return null;
        }
        UUID existing = MEMBER_TEAM.get(leader);
        if (existing != null) {
            return null;
        }
        RtsTeam team = new RtsTeam(UUID.randomUUID(), owner, leader);
        MEMBER_TEAM.put(leader, team.teamId());
        if (members != null) {
            for (UUID member : members) {
                join(team, member);
            }
        }
        TEAMS.put(team.teamId(), team);
        return team;
    }

    public static boolean join(RtsTeam team, UUID member) {
        if (team == null || member == null || MEMBER_TEAM.containsKey(member)) {
            return false;
        }
        if (!team.add(member)) {
            return false;
        }
        MEMBER_TEAM.put(member, team.teamId());
        return true;
    }

    public static boolean leave(UUID teamId, UUID member) {
        RtsTeam team = TEAMS.get(teamId);
        if (team == null || member == null) {
            return false;
        }
        if (!team.remove(member)) {
            return false;
        }
        MEMBER_TEAM.remove(member);
        if (team.members().isEmpty()) {
            dissolve(teamId);
        }
        return true;
    }

    public static void dissolve(UUID teamId) {
        RtsTeam team = TEAMS.remove(teamId);
        if (team == null) {
            return;
        }
        for (UUID member : team.members()) {
            MEMBER_TEAM.remove(member);
        }
    }

    public static RtsTeam get(UUID teamId) {
        return teamId == null ? null : TEAMS.get(teamId);
    }

    public static RtsTeam teamOf(UUID member) {
        UUID teamId = member == null ? null : MEMBER_TEAM.get(member);
        return teamId == null ? null : TEAMS.get(teamId);
    }

    public static boolean inTeam(UUID member) {
        return member != null && MEMBER_TEAM.containsKey(member);
    }

    public static List<RtsTeam> teamsOf(UUID owner) {
        List<RtsTeam> result = new ArrayList<>();
        for (RtsTeam team : TEAMS.values()) {
            if (team.ownerUuid().equals(owner)) {
                result.add(team);
            }
        }
        return result;
    }

    public static boolean setOffset(UUID teamId, UUID member, float offX, float offZ) {
        RtsTeam team = TEAMS.get(teamId);
        if (team == null || member == null || member.equals(team.leaderUuid())) {
            return false;
        }
        if (!team.contains(member)) {
            return false;
        }
        team.setOffset(member, offX, offZ);
        team.setFormationSaved(false);
        return true;
    }

    public static boolean applyShape(UUID teamId, RtsFormation.Shape shape) {
        RtsTeam team = TEAMS.get(teamId);
        if (team == null) {
            return false;
        }
        team.setShape(shape);
        team.applyShape();
        team.setFormationSaved(false);
        return true;
    }

    public static boolean saveFormation(UUID teamId) {
        RtsTeam team = TEAMS.get(teamId);
        if (team == null) {
            return false;
        }
        team.setFormationSaved(true);
        return true;
    }

    public static void clearAll() {
        TEAMS.clear();
        MEMBER_TEAM.clear();
    }
}
