package com.xy2407.kingdom.server.rts;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RtsTeamMemberSchema;
import com.xy2407.kingdom.common.persistence.RtsTeamSchema;
import com.xy2407.kingdom.common.rts.RtsTeam;

import net.minecraft.server.MinecraftServer;

/**
 * RTS 队伍持久化：队伍一行、成员一行（含相对偏移），整队重写与全量加载
 */
public final class RtsTeamStorage {

    private RtsTeamStorage() {
    }

    public static boolean save(MinecraftServer server, RtsTeam team) {
        if (server == null || team == null || !KingdomPersistence.isReady()) {
            return false;
        }
        String teamId = team.teamId().toString();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RtsTeamSchema.COL_TEAM_ID, teamId);
        values.put(RtsTeamSchema.COL_LEADER_UUID,
                team.leaderUuid() == null ? "" : team.leaderUuid().toString());
        values.put(RtsTeamSchema.COL_OWNER_UUID, team.ownerUuid().toString());
        values.put(RtsTeamSchema.COL_TERRITORY_ID, team.territoryId());
        values.put(RtsTeamSchema.COL_FORMATION_SAVED, team.formationSaved() ? 1 : 0);
        values.put(RtsTeamSchema.COL_CREATED_AT, System.currentTimeMillis());
        values.put(RtsTeamSchema.COL_PATROL_NODES, encodeNodes(team.patrolNodes()));
        values.put(RtsTeamSchema.COL_PATROL_INDEX, team.patrolIndex());
        values.put(RtsTeamSchema.COL_PATROL_FORWARD, team.patrolForward());
        values.put(RtsTeamSchema.COL_PATROL_ACTIVE, team.patrolActive() ? 1 : 0);
        KingdomPersistence.submit(RtsTeamSchema.TABLE_TEAM, RtsTeamSchema.COL_TEAM_ID, teamId, values);

        try {
            KingdomPersistence.querySync(connection -> {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM " + RtsTeamMemberSchema.TABLE_MEMBER + " WHERE "
                                + RtsTeamMemberSchema.COL_TEAM_ID + "=?")) {
                    del.setString(1, teamId);
                    del.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR REPLACE INTO " + RtsTeamMemberSchema.TABLE_MEMBER + "("
                                + RtsTeamMemberSchema.columns() + ") VALUES(?,?,?,?,?)")) {
                    for (UUID member : team.members()) {
                        float[] offset = team.offsetOf(member);
                        String memberId = member.toString();
                        insert.setString(1, RtsTeamMemberSchema.keyOf(teamId, memberId));
                        insert.setString(2, teamId);
                        insert.setString(3, memberId);
                        insert.setDouble(4, offset[0]);
                        insert.setDouble(5, offset[1]);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException exception) {
            ModMain.LOGGER.error("重写队伍成员失败 {}", teamId, exception);
            return false;
        }
        return true;
    }

    /**
     * 只写巡逻相关列：路线/下标/方向/是否在执行。
     * 每过一个巡逻节点都会调一次，不能顺带重写成员表（那是全量同步，太重）。
     */
    public static void savePatrol(MinecraftServer server, RtsTeam team) {
        if (server == null || team == null || !KingdomPersistence.isReady()) {
            return;
        }
        String teamId = team.teamId().toString();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RtsTeamSchema.COL_TEAM_ID, teamId);
        values.put(RtsTeamSchema.COL_PATROL_NODES, encodeNodes(team.patrolNodes()));
        values.put(RtsTeamSchema.COL_PATROL_INDEX, team.patrolIndex());
        values.put(RtsTeamSchema.COL_PATROL_FORWARD, team.patrolForward());
        values.put(RtsTeamSchema.COL_PATROL_ACTIVE, team.patrolActive() ? 1 : 0);
        KingdomPersistence.submit(RtsTeamSchema.TABLE_TEAM, RtsTeamSchema.COL_TEAM_ID, teamId, values);
    }

    public static boolean delete(MinecraftServer server, UUID teamId) {
        if (server == null || teamId == null || !KingdomPersistence.isReady()) {
            return false;
        }
        String id = teamId.toString();
        try {
            KingdomPersistence.querySync(connection -> {
                try (PreparedStatement st = connection.prepareStatement(
                        "DELETE FROM " + RtsTeamSchema.TABLE_TEAM + " WHERE "
                                + RtsTeamSchema.COL_TEAM_ID + "=?")) {
                    st.setString(1, id);
                    st.executeUpdate();
                }
                try (PreparedStatement st = connection.prepareStatement(
                        "DELETE FROM " + RtsTeamMemberSchema.TABLE_MEMBER + " WHERE "
                                + RtsTeamMemberSchema.COL_TEAM_ID + "=?")) {
                    st.setString(1, id);
                    st.executeUpdate();
                }
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException exception) {
            ModMain.LOGGER.error("删除队伍失败 {}", id, exception);
            return false;
        }
    }

    public static Map<UUID, RtsTeam> loadAll(MinecraftServer server) {
        if (server == null || !KingdomPersistence.isReady()) {
            return Map.of();
        }
        try {
            return KingdomPersistence.querySync(connection -> {
                Map<UUID, RtsTeam> result = new LinkedHashMap<>();
                Map<UUID, UUID> leaders = new HashMap<>();
                Map<UUID, UUID> owners = new HashMap<>();
                Map<UUID, String> territories = new HashMap<>();
                Map<UUID, Boolean> saved = new HashMap<>();
                Map<UUID, LinkedHashSet<UUID>> members = new HashMap<>();
                Map<UUID, Map<UUID, float[]>> offsets = new HashMap<>();
                Map<UUID, List<net.minecraft.core.BlockPos>> patrols = new HashMap<>();
                Map<UUID, Integer> patrolIndexes = new HashMap<>();
                Map<UUID, Integer> patrolForwards = new HashMap<>();
                Map<UUID, Boolean> patrolActives = new HashMap<>();
                try (PreparedStatement st = connection.prepareStatement(
                        "SELECT " + RtsTeamSchema.columns() + " FROM " + RtsTeamSchema.TABLE_TEAM)) {
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            UUID teamId = parse(rs.getString(RtsTeamSchema.COL_TEAM_ID));
                            if (teamId == null) {
                                continue;
                            }
                            leaders.put(teamId, parse(rs.getString(RtsTeamSchema.COL_LEADER_UUID)));
                            owners.put(teamId, parse(rs.getString(RtsTeamSchema.COL_OWNER_UUID)));
                            territories.put(teamId, rs.getString(RtsTeamSchema.COL_TERRITORY_ID));
                            saved.put(teamId, rs.getInt(RtsTeamSchema.COL_FORMATION_SAVED) != 0);
                            members.put(teamId, new LinkedHashSet<>());
                            offsets.put(teamId, new LinkedHashMap<>());
                            patrols.put(teamId, decodeNodes(rs.getString(RtsTeamSchema.COL_PATROL_NODES)));
                            patrolIndexes.put(teamId, rs.getInt(RtsTeamSchema.COL_PATROL_INDEX));
                            patrolForwards.put(teamId, rs.getInt(RtsTeamSchema.COL_PATROL_FORWARD));
                            patrolActives.put(teamId, rs.getInt(RtsTeamSchema.COL_PATROL_ACTIVE) != 0);
                        }
                    }
                }
                try (PreparedStatement st = connection.prepareStatement(
                        "SELECT " + RtsTeamMemberSchema.columns() + " FROM "
                                + RtsTeamMemberSchema.TABLE_MEMBER)) {
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            UUID teamId = parse(rs.getString(RtsTeamMemberSchema.COL_TEAM_ID));
                            UUID member = parse(rs.getString(RtsTeamMemberSchema.COL_MEMBER_UUID));
                            if (teamId == null || member == null || !members.containsKey(teamId)) {
                                continue;
                            }
                            members.get(teamId).add(member);
                            offsets.get(teamId).put(member, new float[]{
                                    (float) rs.getDouble(RtsTeamMemberSchema.COL_OFFSET_X),
                                    (float) rs.getDouble(RtsTeamMemberSchema.COL_OFFSET_Z)});
                        }
                    }
                }
                for (UUID teamId : members.keySet()) {
                    UUID owner = owners.get(teamId);
                    if (owner == null) {
                        continue;
                    }
                    List<UUID> list = new ArrayList<>(members.get(teamId));
                    RtsTeam team = new RtsTeam(teamId, owner, leaders.get(teamId),
                            territories.get(teamId), list, offsets.get(teamId),
                            Boolean.TRUE.equals(saved.get(teamId)));
                    List<net.minecraft.core.BlockPos> nodes = patrols.get(teamId);
                    if (nodes != null && nodes.size() >= 2) {
                        team.setPatrol(nodes,
                                patrolIndexes.getOrDefault(teamId, 0),
                                patrolForwards.getOrDefault(teamId, 1));
                        if (!Boolean.TRUE.equals(patrolActives.get(teamId))) {
                            // 路线还留着但任务已被别打了：不自动恢复，等玩家再点巡逻
                            team.endPatrol();
                        }
                    }
                    result.put(teamId, team);
                }
                return result;
            });
        } catch (SQLException exception) {
            ModMain.LOGGER.error("加载 RTS 队伍失败", exception);
            return Map.of();
        }
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 巡逻路线落库格式："x,y,z;x,y,z;..."，空格是节点分隔符。 */
    static String encodeNodes(List<net.minecraft.core.BlockPos> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(nodes.size() * 12);
        for (net.minecraft.core.BlockPos node : nodes) {
            if (node == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(node.getX()).append(',').append(node.getY()).append(',').append(node.getZ());
        }
        return builder.toString();
    }

    static List<net.minecraft.core.BlockPos> decodeNodes(String raw) {
        List<net.minecraft.core.BlockPos> nodes = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return nodes;
        }
        for (String part : raw.split(";")) {
            String[] fields = part.split(",");
            if (fields.length != 3) {
                continue;
            }
            try {
                nodes.add(new net.minecraft.core.BlockPos(
                        Integer.parseInt(fields[0].trim()),
                        Integer.parseInt(fields[1].trim()),
                        Integer.parseInt(fields[2].trim())));
            } catch (NumberFormatException ignored) {
                // 坏数据跳过，别让一条脏路线拖垮整张队伍表
            }
        }
        return nodes;
    }
}
