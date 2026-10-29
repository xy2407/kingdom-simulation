package com.xy2407.kingdom.server.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.rts.RtsFormation;
import com.xy2407.kingdom.common.rts.RtsTeam;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * RTS 队伍管理器：内存缓存与落库、一人一队、阵型编辑冻结、队长跟随与成员增删
 */
public final class RtsTeamManager {

    private static final Map<UUID, RtsTeam> TEAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> MEMBER_TEAM = new ConcurrentHashMap<>();
    private static final Set<UUID> EDITING = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> WORLD_OPS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> EDIT_MEMBERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Double> EDIT_SPEED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STAND = new ConcurrentHashMap<>();

    private static final double FOLLOW_ARRIVE_SQR = 4.0D;
    private static final double SPACING = 2.0D;

    private RtsTeamManager() {
    }

    public record CreateResult(boolean success, List<UUID> skipped) {
    }

    public record LeaderPos(UUID leaderUuid, Vec3 pos) {
    }

    public static void reload(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return;
        }
        Map<UUID, RtsTeam> loaded = RtsTeamStorage.loadAll(level.getServer());
        TEAMS.clear();
        MEMBER_TEAM.clear();
        TEAMS.putAll(loaded);
        for (RtsTeam team : TEAMS.values()) {
            for (UUID member : team.members()) {
                MEMBER_TEAM.put(member, team.teamId());
            }
        }
    }

    public static CreateResult create(ServerLevel level, ServerPlayer player,
                                      UUID leaderUuid, List<UUID> memberIds) {
        if (level == null || player == null || leaderUuid == null) {
            return new CreateResult(false, List.of());
        }
        String territoryId = territoryOf(player);
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        unique.add(leaderUuid);
        if (memberIds != null) {
            unique.addAll(memberIds);
        }
        LinkedHashSet<UUID> accepted = new LinkedHashSet<>();
        List<UUID> skipped = new ArrayList<>();
        for (UUID id : unique) {
            if (id == null) {
                continue;
            }
            if (MEMBER_TEAM.containsKey(id)) {
                skipped.add(id);
                continue;
            }
            Entity entity = findAcrossLevels(level.getServer(), id);
            if (!(entity instanceof NpcEntity npc) || npc.isTouristEntity()) {
                skipped.add(id);
                continue;
            }
            if (!RtsSelectionService.canControl(player, entity)) {
                skipped.add(id);
                continue;
            }
            accepted.add(id);
        }
        if (accepted.isEmpty() || !accepted.contains(leaderUuid)) {
            return new CreateResult(false, skipped);
        }
        UUID teamId = UUID.randomUUID();
        RtsTeam team = new RtsTeam(teamId, player.getUUID(), leaderUuid, territoryId,
                new ArrayList<>(accepted), Map.of(), false);
        if (!RtsTeamStorage.save(level.getServer(), team)) {
            return new CreateResult(false, skipped);
        }
        TEAMS.put(teamId, team);
        for (UUID member : accepted) {
            MEMBER_TEAM.put(member, teamId);
        }
        return new CreateResult(true, skipped);
    }

    public static boolean delete(ServerLevel level, ServerPlayer player, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || player == null || !team.ownerUuid().equals(player.getUUID())) {
            return false;
        }
        clearMembers(level, team);
        boolean ok = RtsTeamStorage.delete(level.getServer(), teamId);
        TEAMS.remove(teamId);
        return ok;
    }

    public static void dissolveTeam(ServerLevel level, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return;
        }
        clearMembers(level, team);
        if (level.getServer() != null) {
            RtsTeamStorage.delete(level.getServer(), teamId);
        }
        TEAMS.remove(teamId);
    }

    private static void clearMembers(ServerLevel level, RtsTeam team) {
        EDITING.remove(team.teamId());
        for (UUID member : new ArrayList<>(team.members())) {
            MEMBER_TEAM.remove(member);
            LAST_STAND.remove(member);
            restoreEditSpeed(findAcrossLevels(level.getServer(), member));
        }
    }

    public static boolean removeMemberOnDeath(ServerLevel level, UUID memberUuid) {
        UUID teamId = memberUuid == null ? null : MEMBER_TEAM.get(memberUuid);
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            MEMBER_TEAM.remove(memberUuid);
            return false;
        }
        if (memberUuid.equals(team.leaderUuid())) {
            dissolveTeam(level, teamId);
            return true;
        }
        team.remove(memberUuid);
        MEMBER_TEAM.remove(memberUuid);
        LAST_STAND.remove(memberUuid);
        restoreEditSpeed(findAcrossLevels(level.getServer(), memberUuid));
        RtsTeamStorage.save(level.getServer(), team);
        RtsTeamService.syncToOwner(level, team);
        return true;
    }

    public static RtsTeam get(UUID teamId) {
        return teamId == null ? null : TEAMS.get(teamId);
    }

    public static void clearStands(UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return;
        }
        for (UUID member : team.members()) {
            LAST_STAND.remove(member);
        }
    }

    public static UUID teamIdOf(UUID memberUuid) {
        return memberUuid == null ? null : MEMBER_TEAM.get(memberUuid);
    }

    public static boolean isInTeam(UUID memberUuid) {
        return memberUuid != null && MEMBER_TEAM.containsKey(memberUuid);
    }

    public static List<RtsTeam> teamsOf(UUID ownerUuid) {
        List<RtsTeam> list = new ArrayList<>();
        if (ownerUuid == null) {
            return list;
        }
        for (RtsTeam team : TEAMS.values()) {
            if (team.ownerUuid().equals(ownerUuid)) {
                list.add(team);
            }
        }
        list.sort((left, right) -> left.teamId().compareTo(right.teamId()));
        return list;
    }

    public static List<RtsTeam> all() {
        return new ArrayList<>(TEAMS.values());
    }

    public static void clearCache() {
        TEAMS.clear();
        MEMBER_TEAM.clear();
        EDITING.clear();
        WORLD_OPS.clear();
        EDIT_MEMBERS.clear();
        EDIT_SPEED.clear();
        LAST_STAND.clear();
    }

    public static boolean setMemberOffset(UUID teamId, UUID member, float offX, float offZ) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
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

    public static boolean saveFormation(MinecraftServer server, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return false;
        }
        team.setFormationSaved(true);
        return RtsTeamStorage.save(server, team);
    }

    public static boolean applyShape(UUID teamId, RtsFormation.Shape shape) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return false;
        }
        team.setShape(shape);
        team.applyShape();
        team.setFormationSaved(false);
        return true;
    }

    public static boolean isEditing(UUID teamId) {
        return teamId != null && EDITING.contains(teamId);
    }

    public static boolean isEditFrozen(UUID memberId) {
        return memberId != null && EDIT_MEMBERS.contains(memberId);
    }

    public static void freezeTeamForEdit(ServerLevel level, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || team.members().isEmpty()) {
            return;
        }
        EDITING.add(teamId);
        WORLD_OPS.remove(teamId);
        for (UUID member : team.members()) {
            EDIT_MEMBERS.add(member);
            Entity entity = findAcrossLevels(level.getServer(), member);
            if (entity instanceof NpcEntity npc) {
                alignToBlockCenter(npc);
                if (npc.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
                    EDIT_SPEED.putIfAbsent(member,
                            npc.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue());
                    npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
                }
                npc.clearMoveTargets();
                stopEntity(npc);
            }
        }
    }

    public static void setWorldOps(ServerLevel level, UUID teamId, boolean worldOps) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return;
        }
        if (!worldOps) {
            freezeTeamForEdit(level, teamId);
            return;
        }
        if (!EDITING.contains(teamId)) {
            return;
        }
        WORLD_OPS.add(teamId);
        for (UUID member : new ArrayList<>(team.members())) {
            NpcEntity npc = findAcrossLevels(level.getServer(), member);
            restoreEditSpeed(npc);
            if (npc != null) {
                npc.clearMoveTargets();
            }
        }
    }

    public static boolean isWorldOps(UUID teamId) {
        return teamId != null && WORLD_OPS.contains(teamId);
    }

    private static void alignToBlockCenter(NpcEntity npc) {
        double centerX = Math.floor(npc.getX()) + 0.5D;
        double centerZ = Math.floor(npc.getZ()) + 0.5D;
        if (Math.abs(npc.getX() - centerX) < 1.0E-3D
                && Math.abs(npc.getZ() - centerZ) < 1.0E-3D) {
            return;
        }
        npc.setPos(centerX, npc.getY(), centerZ);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.getNavigation().stop();
        npc.clearNavTarget();
    }

    public static void releaseTeamAfterEdit(ServerLevel level, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null) {
            return;
        }
        EDITING.remove(teamId);
        WORLD_OPS.remove(teamId);
        for (UUID member : new ArrayList<>(team.members())) {
            EDIT_MEMBERS.remove(member);
            NpcEntity npc = findAcrossLevels(level.getServer(), member);
            restoreEditSpeed(npc);
            if (npc != null) {
                npc.clearMoveTargets();
            }
            LAST_STAND.remove(member);
        }
        startFollow(level, teamId);
    }

    private static void stopEntity(NpcEntity npc) {
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.setZza(0.0F);
        npc.setXxa(0.0F);
        npc.setJumping(false);
    }

    private static void restoreEditSpeed(NpcEntity npc) {
        if (npc == null) {
            return;
        }
        Double origin = EDIT_SPEED.remove(npc.getUUID());
        if (origin != null && npc.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(origin);
        }
    }

    public static NpcEntity findAcrossLevels(MinecraftServer server, UUID id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof NpcEntity npc) {
                return npc;
            }
        }
        return null;
    }

    public static Entity findEntityAcrossLevels(MinecraftServer server, UUID id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    public static LeaderPos leaderPosition(ServerLevel level, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || team.leaderUuid() == null) {
            return null;
        }
        Entity leader = findEntityAcrossLevels(level.getServer(), team.leaderUuid());
        return leader == null ? null : new LeaderPos(team.leaderUuid(), leader.position());
    }

    public static String join(ServerLevel level, ServerPlayer player, UUID teamId, UUID memberUuid) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || player == null || !team.ownerUuid().equals(player.getUUID())) {
            return "无权限操作该队伍";
        }
        if (MEMBER_TEAM.containsKey(memberUuid)) {
            return "该市民已在队伍中，请先退出再加入";
        }
        Entity entity = findEntityAcrossLevels(level.getServer(), memberUuid);
        if (!(entity instanceof NpcEntity npc) || npc.isTouristEntity()) {
            return "找不到该市民";
        }
        if (!RtsSelectionService.canControl(player, entity)) {
            return "无法控制该市民";
        }
        team.add(memberUuid);
        team.setOffset(memberUuid, 0.0F, 0.0F);
        if (!RtsTeamStorage.save(level.getServer(), team)) {
            team.remove(memberUuid);
            return "加入失败：数据库写入失败";
        }
        MEMBER_TEAM.put(memberUuid, teamId);
        startFollow(level, teamId);
        return null;
    }

    public static String leave(ServerLevel level, ServerPlayer player, UUID teamId, UUID memberUuid) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || player == null || !team.ownerUuid().equals(player.getUUID())) {
            return "无权限操作该队伍";
        }
        if (!team.contains(memberUuid)) {
            return "该市民不在此队伍";
        }
        if (memberUuid.equals(team.leaderUuid())) {
            clearMembers(level, team);
            boolean ok = RtsTeamStorage.delete(level.getServer(), teamId);
            TEAMS.remove(teamId);
            return ok ? null : "退出失败：数据库写入失败";
        }
        team.remove(memberUuid);
        MEMBER_TEAM.remove(memberUuid);
        LAST_STAND.remove(memberUuid);
        restoreEditSpeed(findAcrossLevels(level.getServer(), memberUuid));
        if (!RtsTeamStorage.save(level.getServer(), team)) {
            return "退出失败：数据库写入失败";
        }
        return null;
    }

    private static float[] spiralOffset(int index) {
        if (index <= 0) {
            return new float[]{0.0F, 0.0F};
        }
        double radius = SPACING * Math.sqrt(index);
        double angle = index * 2.39996D;
        return new float[]{(float) (Math.cos(angle) * radius), (float) (Math.sin(angle) * radius)};
    }

    public static void startFollow(ServerLevel level, UUID teamId) {
        RtsTeam team = teamId == null ? null : TEAMS.get(teamId);
        if (team == null || team.members().isEmpty()) {
            return;
        }
        Entity leader = findEntityAcrossLevels(level.getServer(), team.leaderUuid());
        if (leader == null) {
            return;
        }
        boolean hasFormation = team.formationSaved();
        for (UUID member : team.members()) {
            if (member.equals(team.leaderUuid())) {
                continue;
            }
            NpcEntity npc = findAcrossLevels(level.getServer(), member);
            if (npc == null || npc.isRemoved()) {
                continue;
            }
            float[] offset = hasFormation ? team.offsetOf(member)
                    : spiralOffset(team.members().indexOf(member));
            BlockPos stand = BlockPos.containing(leader.getX() + offset[0],
                    leader.getY(), leader.getZ() + offset[1]);
            LAST_STAND.put(member, stand.asLong());
            npc.setRtsTarget(stand, false);
        }
    }

    public static void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (RtsTeam team : TEAMS.values()) {
            if (team.members().isEmpty()) {
                continue;
            }
            if (EDITING.contains(team.teamId())) {
                if (WORLD_OPS.contains(team.teamId())) {
                    continue;
                }
                for (UUID member : team.members()) {
                    NpcEntity npc = findAcrossLevels(level.getServer(), member);
                    if (npc != null) {
                        stopEntity(npc);
                    }
                }
                continue;
            }
            Entity leader = findEntityAcrossLevels(level.getServer(), team.leaderUuid());
            if (leader == null) {
                continue;
            }
            boolean hasFormation = team.formationSaved();
            for (UUID member : team.members()) {
                if (member.equals(team.leaderUuid())) {
                    continue;
                }
                NpcEntity npc = findAcrossLevels(level.getServer(), member);
                if (npc == null || npc.isRemoved()) {
                    continue;
                }
                float[] offset = hasFormation ? team.offsetOf(member)
                        : spiralOffset(team.members().indexOf(member));
                BlockPos stand = BlockPos.containing(leader.getX() + offset[0],
                        leader.getY(), leader.getZ() + offset[1]);
                if (npc.blockPosition().distSqr(stand) <= FOLLOW_ARRIVE_SQR) {
                    continue;
                }
                Long previous = LAST_STAND.get(member);
                if (controlledByRts(npc) && previous != null && previous == stand.asLong()) {
                    continue;
                }
                LAST_STAND.put(member, stand.asLong());
                npc.setRtsTarget(stand, false);
            }
        }
    }

    private static boolean controlledByRts(NpcEntity npc) {
        if (npc.getCitizenId() == null) {
            return false;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        return data != null && data.getBrain().isRtsControlled();
    }

    private static String territoryOf(ServerPlayer player) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        return profile == null ? "" : profile.territoryId();
    }
}
