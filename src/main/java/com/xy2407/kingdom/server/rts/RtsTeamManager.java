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
    /** 巡逻纵队的到达半径更紧（1 格），否则 3 列纵队会挤成一团。 */
    private static final double PATROL_ARRIVE_SQR = 1.0D;
    private static final double SPACING = 2.0D;
    public static final double TEAM_LEASH = 12.0D;
    private static final double TEAM_PULLBACK = 14.0D;
    private static final Map<UUID, BlockPos> ANCHORS = new ConcurrentHashMap<>();

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
        // 队伍解散/成员清空时一并撤掉巡逻安排，否则会留下没有队伍的幽灵路线
        RtsPatrolService.stop(level, team.teamId());
        for (UUID member : new ArrayList<>(team.members())) {
            MEMBER_TEAM.remove(member);
            LAST_STAND.remove(member);
            ANCHORS.remove(member);
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
        MEMBER_TEAM.remove(memberUuid);
        LAST_STAND.remove(memberUuid);
        ANCHORS.remove(memberUuid);
        restoreEditSpeed(findAcrossLevels(level.getServer(), memberUuid));
        // 队长阵亡：RtsTeam.remove 会自动把队内下一个成员顶上队长位，队伍不解散
        team.remove(memberUuid);
        if (team.members().isEmpty()) {
            // 全队死光 → 自动解散，并明确告诉玩家（否则队伍会凭空消失）
            clearMembers(level, team);
            RtsTeamStorage.delete(level.getServer(), teamId);
            TEAMS.remove(teamId);
            notifyTeamWiped(level, team);
            return true;
        }
        RtsTeamStorage.save(level.getServer(), team);
        RtsTeamService.syncToOwner(level, team);
        return true;
    }

    /** 队伍全员阵亡自动解散的提示：只发给还活着的队主。 */
    private static void notifyTeamWiped(ServerLevel level, RtsTeam team) {
        if (level == null || team == null || level.getServer() == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(team.ownerUuid());
        if (owner != null) {
            owner.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "你的 RTS 队伍全员阵亡，队伍已自动解散"), false);
        }
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
        ANCHORS.clear();
        RtsPatrolService.clearAll();
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
        // 进阵型编辑就撤掉巡逻：编辑期速度被钉成 0，巡逻路线推进会与摆格子互相干扰。
        // 只暂停任务、保留路线，编辑完还能接着巡逻。
        if (RtsPatrolService.interrupt(level, teamId)) {
            RtsTeamService.syncToOwner(level, team);
        }
        EDITING.add(teamId);
        WORLD_OPS.remove(teamId);
        for (UUID member : team.members()) {
            EDIT_MEMBERS.add(member);
            Entity entity = findAcrossLevels(level.getServer(), member);
            if (entity instanceof NpcEntity npc) {
                // 编辑期间（含世界空间模式）的移动一律用精确抵达（< 0.1 格）：
                // 玩家是在摆格子，停在半格或三格外没有意义。退出编辑时恢复普通 RTS 阈值。
                npc.setRtsPrecise(true);
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

    /**
     * 把成员直接放到目标格的中心（阵型编辑专用）。
     *
     * <p>编辑期间移动速度被钉成 0（见 {@link #freezeTeamForEdit}），靠 setRtsTarget 让 NPC 自己走过去
     * 是走不动的；玩家的意图是"把小方块摆进这一格"，所以必须瞬移，而不是下发寻路指令。
     */
    public static void teleportMemberToCell(ServerLevel level, NpcEntity npc, BlockPos cell) {
        if (npc == null || cell == null) {
            return;
        }
        double x = cell.getX() + 0.5D;
        double y = cell.getY();
        double z = cell.getZ() + 0.5D;
        stopEntity(npc);
        npc.getNavigation().stop();
        npc.clearMoveTargets();
        npc.setDeltaMovement(Vec3.ZERO);
        // teleportTo 走实体传送流程，位置能立刻同步给客户端
        npc.teleportTo(x, y, z);
        npc.setPos(x, y, z);
        npc.setDeltaMovement(Vec3.ZERO);
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
                // 退出编辑：回到普通 RTS 的抵达阈值（3 格），精确模式只服务于摆格子
                npc.setRtsPrecise(false);
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
        ANCHORS.remove(memberUuid);
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
            ANCHORS.put(member, stand);
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
            // 巡逻交战中：整队由战斗驱动，队形跟随让位，否则每拍重发的站位指令会把追击的人拽回来
            if (RtsPatrolService.isEngaged(team.teamId())) {
                continue;
            }
            // 巡逻进行中：站位换成巡逻的 3 列纵队（队长领头），队伍自己保存的阵型偏移一个字都不改，
            // 巡逻一结束这里就自动回到原阵型。
            boolean patrolling = RtsPatrolService.isPatrolling(team.teamId());
            NpcEntity leaderNpc = leader instanceof NpcEntity ? (NpcEntity) leader : null;
            boolean hasFormation = team.formationSaved();
            for (UUID member : team.members()) {
                if (member.equals(team.leaderUuid())) {
                    continue;
                }
                NpcEntity npc = findAcrossLevels(level.getServer(), member);
                if (npc == null || npc.isRemoved()) {
                    continue;
                }
                BlockPos stand;
                if (patrolling) {
                    stand = RtsPatrolService.patrolStand(team, leaderNpc, member);
                } else {
                    float[] offset = hasFormation ? team.offsetOf(member)
                            : spiralOffset(team.members().indexOf(member));
                    stand = BlockPos.containing(leader.getX() + offset[0],
                            leader.getY(), leader.getZ() + offset[1]);
                }
                if (stand == null) {
                    continue;
                }
                ANCHORS.put(member, stand);
                if (RtsOpsService.isHolding(member)) {
                    continue;
                }
                boolean fighting = npc.combatEngaged();
                double selfSqr = distanceSqrToStand(npc, stand);
                if (fighting) {
                    if (selfSqr <= TEAM_PULLBACK * TEAM_PULLBACK) {
                        continue;
                    }
                } else if (selfSqr <= (patrolling ? PATROL_ARRIVE_SQR : FOLLOW_ARRIVE_SQR)) {
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

    public static BlockPos anchorOf(UUID member) {
        return member == null ? null : ANCHORS.get(member);
    }

    private static double distanceSqrToStand(NpcEntity npc, BlockPos stand) {
        double dx = npc.getX() - (stand.getX() + 0.5D);
        double dy = npc.getY() - stand.getY();
        double dz = npc.getZ() - (stand.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
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
