package com.xy2407.kingdom.server.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.rts.RtsTeamMemberPosPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamOpPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamOpResultPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamRosterPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.rts.RtsFormation;
import com.xy2407.kingdom.common.rts.RtsTeam;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * RTS 队伍服务：建队、成员增删、阵型编辑冻结与保存、每 10 tick 队长跟随
 */
public final class RtsTeamService {

    private static final int FOLLOW_INTERVAL_TICKS = 10;
    private static final long EDIT_TIMEOUT_MS = 300_000L;

    private static final Map<UUID, Long> EDIT_SINCE = new ConcurrentHashMap<>();
    private static int tickCounter;

    private RtsTeamService() {
    }

    public static void reset() {
        RtsTeamManager.clearCache();
        EDIT_SINCE.clear();
        tickCounter = 0;
    }

    public static void load(ServerLevel level) {
        RtsTeamManager.reload(level);
    }

    public static void createFromSelection(ServerPlayer player, List<UUID> requested, int shape) {
        if (player == null) {
            return;
        }
        List<UUID> source = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            source.addAll(requested);
        } else {
            source.addAll(RtsSelectionService.getSelected(player));
        }
        if (source.isEmpty()) {
            sync(player);
            return;
        }
        UUID leader = source.get(0);
        RtsTeamManager.CreateResult result = RtsTeamManager.create(
                player.serverLevel(), player, leader, source.subList(1, source.size()));
        if (result.success()) {
            if (shape > 0) {
                for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
                    if (leader.equals(team.leaderUuid())) {
                        RtsTeamManager.applyShape(team.teamId(), RtsFormation.Shape.fromId(shape));
                        break;
                    }
                }
            }
            result(player, true, "队伍已创建：" + (source.size() - result.skipped().size()) + " 人");
        } else {
            result(player, false, result.skipped().isEmpty()
                    ? "建队失败：至少需要 2 名可控市民，且不能已在别的队伍"
                    : "建队失败：" + result.skipped().size() + " 人已在队伍中或不可控");
        }
        sync(player);
    }

    public static void editMember(ServerPlayer player, UUID teamId, UUID member,
                                  float offX, float offZ) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        if (!RtsTeamManager.setMemberOffset(teamId, member, offX, offZ)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        RtsTeamManager.LeaderPos leader = RtsTeamManager.leaderPosition(level, teamId);
        NpcEntity npc = RtsTeamManager.findAcrossLevels(level.getServer(), member);
        if (leader != null && npc != null) {
            npc.setRtsTarget(BlockPos.containing(leader.pos().x + offX,
                    leader.pos().y, leader.pos().z + offZ), false);
        }
        sync(player);
    }

    public static void setEditing(ServerPlayer player, UUID teamId, boolean editing) {
        setEditing(player, teamId, editing, false);
    }

    public static void setEditing(ServerPlayer player, UUID teamId, boolean editing,
                                  boolean worldOps) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        if (!editing) {
            EDIT_SINCE.remove(teamId);
            RtsTeamManager.releaseTeamAfterEdit(player.serverLevel(), teamId);
            return;
        }
        EDIT_SINCE.put(teamId, System.currentTimeMillis());
        RtsTeamManager.freezeTeamForEdit(player.serverLevel(), teamId);
        RtsTeamManager.setWorldOps(player.serverLevel(), teamId, worldOps);
    }

    public static void apply(ServerPlayer player, UUID teamId, int action, int shape) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            sync(player);
            return;
        }
        switch (action) {
            case RtsTeamOpPacket.ACTION_SAVE -> {
                RtsTeamManager.saveFormation(player.getServer(), teamId);
                RtsTeamManager.startFollow(player.serverLevel(), teamId);
            }
            case RtsTeamOpPacket.ACTION_DISSOLVE -> {
                EDIT_SINCE.remove(teamId);
                RtsTeamManager.dissolveTeam(player.serverLevel(), teamId);
            }
            case RtsTeamOpPacket.ACTION_SHAPE -> {
                RtsTeamManager.applyShape(teamId, RtsFormation.Shape.fromId(shape));
                RtsTeamManager.startFollow(player.serverLevel(), teamId);
            }
            default -> {
            }
        }
        sync(player);
    }

    public static void releaseEditingOf(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
            if (RtsTeamManager.isEditing(team.teamId())) {
                EDIT_SINCE.remove(team.teamId());
                RtsTeamManager.releaseTeamAfterEdit(player.serverLevel(), team.teamId());
            }
        }
    }

    public static void delete(ServerPlayer player, UUID teamId) {
        if (player == null) {
            return;
        }
        EDIT_SINCE.remove(teamId);
        boolean ok = RtsTeamManager.delete(player.serverLevel(), player, teamId);
        result(player, ok, ok ? "队伍已解散" : "解散失败：无权限或队伍不存在");
        sync(player);
    }

    public static void moveTeam(ServerPlayer player, UUID teamId, BlockPos target) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || player == null || target == null
                || !team.ownerUuid().equals(player.getUUID())) {
            result(player, false, "移动失败：无权限或目标无效");
            return;
        }
        int moved = 0;
        for (UUID member : team.members()) {
            NpcEntity npc = RtsTeamManager.findAcrossLevels(player.getServer(), member);
            if (npc == null) {
                continue;
            }
            float[] offset = team.offsetFor(member);
            BlockPos stand = target.offset(Math.round(offset[0]), 0, Math.round(offset[1]));
            npc.setRtsTarget(stand, false);
            moved++;
        }
        RtsTeamManager.clearStands(teamId);
        result(player, moved > 0, moved > 0
                ? "已按阵型下令移动 " + moved + " 人"
                : "移动失败：队伍成员不在已加载区域");
    }

    private static void collectRoster(ServerPlayer player, RtsTeam team,
                                      List<RtsTeamRosterPacket.Member> out, Set<UUID> seen) {
        if (team == null) {
            return;
        }
        for (UUID member : team.members()) {
            if (!seen.add(member)) {
                continue;
            }
            NpcEntity npc = RtsTeamManager.findAcrossLevels(player.getServer(), member);
            NpcData data = npc == null || npc.getCitizenId() == null
                    ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
            String name = data == null ? "" : data.getName();
            if (name == null || name.isBlank()) {
                name = "市民" + member.toString().substring(0, 4);
            }
            out.add(new RtsTeamRosterPacket.Member(member, name,
                    data == null ? "" : data.getSkin(),
                    data == null ? "" : data.getJob()));
        }
    }

    private static void result(ServerPlayer player, boolean success, String message) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new RtsTeamOpResultPacket(success, message));
        }
    }

    public static String join(ServerPlayer player, UUID teamId, UUID member) {
        if (player == null) {
            return "玩家不存在";
        }
        String error = RtsTeamManager.join(player.serverLevel(), player, teamId, member);
        sync(player);
        return error;
    }

    public static String leave(ServerPlayer player, UUID teamId, UUID member) {
        if (player == null) {
            return "玩家不存在";
        }
        String error = RtsTeamManager.leave(player.serverLevel(), player, teamId, member);
        sync(player);
        return error;
    }

    public static void focus(ServerPlayer player, UUID teamId) {
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || player == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        if (team.leaderUuid() == null) {
            return;
        }
        Set<UUID> ids = Set.of(team.leaderUuid());
        RtsSelectionService.setSelected(player, ids);
        PacketDistributor.sendToPlayer(player,
                new com.xy2407.kingdom.common.network.rts.RtsSelectionCorrectionPacket(ids));
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        expireEditing(server);
        if (++tickCounter % FOLLOW_INTERVAL_TICKS != 0) {
            return;
        }
        if (tickCounter % 20 == 0) {
            pushMemberPositions(server);
        }
        for (ServerLevel level : server.getAllLevels()) {
            RtsTeamManager.tick(level);
        }
    }

    private static void expireEditing(MinecraftServer server) {
        if (EDIT_SINCE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : EDIT_SINCE.entrySet()) {
            if (now - entry.getValue() <= EDIT_TIMEOUT_MS) {
                continue;
            }
            UUID teamId = entry.getKey();
            EDIT_SINCE.remove(teamId);
            RtsTeam team = RtsTeamManager.get(teamId);
            if (team != null) {
                RtsTeamManager.releaseTeamAfterEdit(server.overworld(), teamId);
            }
        }
    }

    public static void sync(ServerPlayer player) {
        if (player == null) {
            return;
        }
        List<RtsTeamSyncPacket.Entry> entries = new ArrayList<>();
        for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
            entries.add(new RtsTeamSyncPacket.Entry(team.teamId(), team.leaderUuid(),
                    team.members(), team.shape().id, team.formationSaved()));
        }
        PacketDistributor.sendToPlayer(player, new RtsTeamSyncPacket(entries));
        List<RtsTeamRosterPacket.Member> roster = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
            collectRoster(player, team, roster, seen);
        }
        if (!roster.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new RtsTeamRosterPacket(roster));
        }
    }

    public static void syncToOwner(ServerLevel level, RtsTeam team) {
        if (level == null || team == null || level.getServer() == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(team.ownerUuid());
        if (owner != null) {
            sync(owner);
        }
    }

    public static void pushMemberPositions(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (RtsTeam team : RtsTeamManager.teamsOf(player.getUUID())) {
                if (team.members().isEmpty()) {
                    continue;
                }
                List<RtsTeamMemberPosPacket.MemberPos> positions = new ArrayList<>();
                for (UUID member : team.members()) {
                    Entity entity = RtsTeamManager.findEntityAcrossLevels(server, member);
                    if (entity != null) {
                        positions.add(new RtsTeamMemberPosPacket.MemberPos(member,
                                entity.getX(), entity.getY(), entity.getZ()));
                    }
                }
                if (!positions.isEmpty()) {
                    PacketDistributor.sendToPlayer(player,
                            new RtsTeamMemberPosPacket(team.teamId(), positions));
                }
            }
        }
    }
}
