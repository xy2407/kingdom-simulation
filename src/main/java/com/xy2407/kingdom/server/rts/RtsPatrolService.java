package com.xy2407.kingdom.server.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.rts.RtsPatrolOrderPacket;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.rts.RtsTeam;
import com.xy2407.kingdom.server.combat.ThreatIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 队伍巡逻服务：整队沿一条固定路线折返，遇敌时全队共享目标，打完回到中断的节点继续巡逻。
 * <p>
 * 关键约定：
 * <ul>
 *   <li><b>巡逻是队伍级常驻任务</b>：路线、当前节点下标、折返方向都写在 {@link RtsTeam} 上并落库，
 *       服重启/区块卸载都不会丢；队员没加载时巡逻状态继续保留，等他们回来接着走。</li>
 *   <li><b>路线折返</b>：节点 0 是起点、最后一个节点是终点；走到终点后反向走回起点，循环往复。</li>
 *   <li><b>队列阵型</b>：巡逻期间临时使用「队长领头 + 3 列纵队」，队长比第一排再前 2 格；
 *       队伍自己的阵型偏移一个字节都不动，巡逻结束自动恢复原阵型。</li>
 *   <li><b>路径缓存</b>：每段路（节点对）第一次走完就把算出来的路点存下来，之后每一圈直接复用，
 *       不再重复跑自定义寻路；卡住时丢掉缓存重算。</li>
 *   <li><b>共享目标</b>：任意队员在 {@link #SCAN_RANGE} 内发现敌人，立刻广播给全队（含队长），
 *       全队停下巡逻一起打，追击与索敌半径都以当前巡逻节点为圆心。</li>
 * </ul>
 */
public final class RtsPatrolService {

    /** 索敌与追击半径：巡逻期间队员只认这个圈内的敌人。 */
    public static final double SCAN_RANGE = 32.0D;

    private static final double NODE_ARRIVE = 3.5D;
    private static final double NODE_ARRIVE_Y = 3.0D;
    private static final int STALL_TICKS = 40;
    private static final int ENGAGE_TIMEOUT = 240;
    private static final int RESUME_DELAY = 2;
    private static final int REISSUE_TICKS = 2;

    /** 巡逻纵队：3 列、列距 1.6 格、行距 1.6 格，队长比第一排再前 2 格。 */
    private static final int COLUMNS = 3;
    private static final double COL_SPACING = 1.6D;
    private static final double ROW_SPACING = 1.6D;
    private static final double LEADER_LEAD = 2.0D;

    /** 缓存路线和当前位置对不上时（被击退、传送）就作废，超过这个距离直接重算。 */
    private static final double LEG_CACHE_MAX_OFFSET = 12.0D;

    /** 纵队拉得太长时队长停下来等队伍收拢，收拢到这个距离再继续走。 */
    private static final double COLUMN_MAX_GAP = 10.0D;
    private static final double COLUMN_RESUME_GAP = 5.0D;
    /** 等队伍的上限（巡逻拍数，1 拍 = 10 tick）：队员卡死时不能把整条路线拖着不动。 */
    private static final int COLUMN_WAIT_LIMIT = 10;

    private static final Map<UUID, Patrol> PATROLS = new ConcurrentHashMap<>();

    private RtsPatrolService() {
    }

    /** 单支队伍的巡逻状态。 */
    private static final class Patrol {

        private final List<BlockPos> route;
        private int index;
        private int direction = 1;
        private boolean engaged;
        private int engageTicks;
        private int resumeDelay;
        private int stallTicks;
        /** 纵队被甩太开，队长正在原地等队伍收拢。 */
        private boolean waitingColumn;
        private int waitTicks;
        /** 这一段路已经等超时过，就不要再等了（否则队员卡死会把队长钉在原地）。 */
        private boolean skipColumnWait;
        /** 已经给队长下过指令的那一段路（段键 = 节点下标 + 方向），避免每 tick 重发把走路打断。 */
        private long issuedLeg = Long.MIN_VALUE;
        /** 每段路的路点缓存：走通一次就存下来，后续圈数直接复用。 */
        private final Map<Long, List<NpcPathfinder.Waypoint>> legs = new HashMap<>();

        private Patrol(List<BlockPos> route) {
            this(route, 0, 1);
        }

        private Patrol(List<BlockPos> route, int index, int direction) {
            this.route = route;
            this.direction = direction < 0 ? -1 : 1;
            this.index = Math.max(0, Math.min(route.size() - 1, index));
        }

        private BlockPos node() {
            return route.get(Math.max(0, Math.min(route.size() - 1, index)));
        }

        private long legKey() {
            return ((long) index << 32) | (direction & 0xFFFFFFFFL);
        }

        /** 折返推进：走到端点就掉头，两端各不重复停留。 */
        private void step() {
            if (route.size() <= 1) {
                return;
            }
            int next = index + direction;
            if (next >= route.size()) {
                direction = -1;
                next = Math.max(0, route.size() - 2);
            } else if (next < 0) {
                direction = 1;
                next = Math.min(route.size() - 1, 1);
            }
            index = next;
        }
    }

    // ------------------------------------------------------------------ 指令入口

    public static void apply(ServerPlayer player, UUID teamId, int action, List<Vec3> route) {
        if (player == null || teamId == null) {
            return;
        }
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null || !team.ownerUuid().equals(player.getUUID())) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (action == RtsPatrolOrderPacket.ACTION_CANCEL) {
            stop(level, teamId);
            RtsTeamService.syncToOwner(level, team);
            return;
        }
        List<BlockPos> nodes = normalize(route);
        if (nodes.size() < 2) {
            return;
        }
        Patrol patrol = new Patrol(nodes);
        PATROLS.put(teamId, patrol);
        // 路线是常驻任务：写到队伍上并立刻落库，重启后照样接着走
        team.setPatrol(nodes, 0, 1);
        RtsTeamStorage.savePatrol(level.getServer(), team);
        beginPatrol(level, team);
        issueNode(level, team, patrol);
        RtsTeamService.syncToOwner(level, team);
    }

    private static List<BlockPos> normalize(List<Vec3> route) {
        List<BlockPos> nodes = new ArrayList<>();
        if (route == null) {
            return nodes;
        }
        for (Vec3 point : route) {
            if (point == null) {
                continue;
            }
            BlockPos pos = BlockPos.containing(point);
            if (nodes.isEmpty() || !nodes.get(nodes.size() - 1).equals(pos)) {
                nodes.add(pos);
            }
        }
        return nodes;
    }

    /** 把整队切到「巡逻中」：清坚守/清旧仇恨/清移动指令，交回巡逻服务统一驱动。 */
    private static void beginPatrol(ServerLevel level, RtsTeam team) {
        for (UUID member : team.members()) {
            RtsOpsService.clearHolding(member);
            NpcEntity npc = RtsTeamManager.findAcrossLevels(level.getServer(), member);
            if (npc == null || npc.isRemoved()) {
                continue;
            }
            npc.clearPatrolTarget();
            npc.clearCombatAggro();
            npc.setCombatPatrol();
        }
        RtsTeamManager.clearStands(team.teamId());
    }

    /** 解除巡逻：队员停下并回到「手持武器待命」，队伍阵型自动恢复成保存的那套。 */
    public static void stop(ServerLevel level, UUID teamId) {
        if (teamId == null) {
            return;
        }
        PATROLS.remove(teamId);
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null) {
            return;
        }
        // 删除任务 = 永久删除路线，落库
        team.clearPatrol();
        if (level != null && level.getServer() != null) {
            RtsTeamStorage.savePatrol(level.getServer(), team);
        }
        if (level == null) {
            return;
        }
        for (UUID member : team.members()) {
            NpcEntity npc = RtsTeamManager.findAcrossLevels(level.getServer(), member);
            if (npc == null || npc.isRemoved()) {
                continue;
            }
            npc.clearPatrolTarget();
            npc.clearCombatAggro();
            npc.cancelRtsOrder();
            npc.setCombatStandby();
        }
        RtsTeamManager.clearStands(teamId);
    }

    /** 服务端启动时从队伍表恢复巡逻：哪怕队员所在区块还没加载，任务也照样成立。 */
    public static void restoreAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (RtsTeam team : RtsTeamManager.all()) {
            // 只恢复「任务仍在跑」的路线；被坚守/进攻顶掉的路线保留但不自动续跑
            if (!team.patrolActive()) {
                continue;
            }
            PATROLS.put(team.teamId(), new Patrol(team.patrolNodes(),
                    team.patrolIndex(), team.patrolForward()));
        }
    }

    /**
     * 玩家给某支队伍下了坚守/进攻：巡逻立刻让位，但路线记录保留（那是玩家录的点，
     * 不该被一次指令抹掉），队伍状态改成「有路线、未执行」，重启后也不会自动续跑。
     */
    public static boolean interrupt(ServerLevel level, UUID teamId) {
        if (teamId == null) {
            return false;
        }
        Patrol removed = PATROLS.remove(teamId);
        RtsTeam team = RtsTeamManager.get(teamId);
        if (team == null) {
            return false;
        }
        if (removed == null && !team.patrolActive()) {
            return false;
        }
        team.endPatrol();
        if (level != null && level.getServer() != null) {
            RtsTeamStorage.savePatrol(level.getServer(), team);
        }
        if (removed != null && level != null) {
            for (UUID member : team.members()) {
                NpcEntity npc = RtsTeamManager.findAcrossLevels(level.getServer(), member);
                if (npc != null && !npc.isRemoved()) {
                    npc.clearPatrolTarget();
                }
            }
        }
        RtsTeamManager.clearStands(teamId);
        return true;
    }

    // ------------------------------------------------------------------ 每拍驱动

    public static void tick(ServerLevel level) {
        if (level == null || PATROLS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Patrol> entry : PATROLS.entrySet()) {
            Patrol patrol = entry.getValue();
            RtsTeam team = RtsTeamManager.get(entry.getKey());
            if (team == null || team.members().isEmpty()) {
                PATROLS.remove(entry.getKey());
                continue;
            }
            List<NpcEntity> members = resolve(level, team);
            if (members.isEmpty()) {
                // 队员都不在加载范围内：巡逻任务留着，等他们回来接着走，绝不在这里删任务
                continue;
            }
            ensurePatrolMode(members);
            tickTeam(level, team, patrol, members);
        }
    }

    /** 实体重载/重启后队员会回到待命，处在巡逻队伍里的人要重新挂回巡逻态。 */
    private static void ensurePatrolMode(List<NpcEntity> members) {
        for (NpcEntity npc : members) {
            if (!npc.isPatrolCombatMode()) {
                npc.setCombatPatrol();
            }
        }
    }

    private static void tickTeam(ServerLevel level, RtsTeam team, Patrol patrol, List<NpcEntity> members) {
        if (patrol.engaged) {
            tickEngaged(level, team, patrol, members);
            return;
        }
        if (patrol.resumeDelay > 0) {
            patrol.resumeDelay--;
            return;
        }
        // 每拍（1/2 秒）做一次全队共享索敌：任一队员 32 格内发现敌人，全队一起上
        Entity shared = findSharedTarget(level, members);
        if (shared != null) {
            engage(patrol, members, shared);
            return;
        }
        driveRoute(level, team, patrol);
    }

    /** 交战中：只要还有一个队员锁着目标就继续打；全体脱战即恢复巡逻。 */
    private static void tickEngaged(ServerLevel level, RtsTeam team, Patrol patrol, List<NpcEntity> members) {
        patrol.engageTicks++;
        boolean fighting = false;
        for (NpcEntity npc : members) {
            if (npc.hasPatrolTarget()) {
                fighting = true;
                break;
            }
        }
        if (fighting && patrol.engageTicks <= ENGAGE_TIMEOUT) {
            return;
        }
        patrol.engaged = false;
        patrol.engageTicks = 0;
        patrol.resumeDelay = RESUME_DELAY;
        patrol.stallTicks = 0;
        patrol.waitingColumn = false;
        patrol.waitTicks = 0;
        patrol.skipColumnWait = false;
        for (NpcEntity npc : members) {
            npc.clearPatrolTarget();
        }
        RtsTeamManager.clearStands(team.teamId());
        // 从被打断的节点继续，不重置下标 —— 这就是「恢复巡逻」的全部含义。
        issueNode(level, team, patrol);
    }

    private static void engage(Patrol patrol, List<NpcEntity> members, Entity target) {
        patrol.engaged = true;
        patrol.engageTicks = 0;
        patrol.stallTicks = 0;
        for (NpcEntity npc : members) {
            if (npc.hasRtsOrder()) {
                npc.cancelRtsOrder();
            }
            npc.assignPatrolTarget(target);
        }
    }

    /** 队员被偷袭：立刻把动手的人设成全队共享目标，谁被突脸都不会继续傻站着。 */
    public static void onMemberHurt(NpcEntity member, Entity attacker) {
        engageOn(member, attacker);
    }

    /**
     * 把某个目标变成整队的共享目标（用于「巡逻中被玩家 Ctrl 指定目标」和「被偷袭」）。
     *
     * @return true 表示该队员所在队伍正在巡逻、已经按共享目标处理；false 表示不归巡逻管，调用方走原有逻辑
     */
    public static boolean engageOn(NpcEntity member, Entity target) {
        if (member == null || target == null || !(member.level() instanceof ServerLevel level)) {
            return false;
        }
        UUID teamId = RtsTeamManager.teamIdOf(member.getUUID());
        Patrol patrol = teamId == null ? null : PATROLS.get(teamId);
        RtsTeam team = teamId == null ? null : RtsTeamManager.get(teamId);
        if (patrol == null || team == null) {
            return false;
        }
        List<NpcEntity> members = resolve(level, team);
        if (members.isEmpty()) {
            return false;
        }
        engage(patrol, members, target);
        return true;
    }

    private static List<NpcEntity> resolve(ServerLevel level, RtsTeam team) {
        List<NpcEntity> members = new ArrayList<>();
        for (UUID member : team.members()) {
            NpcEntity npc = RtsTeamManager.findAcrossLevels(level.getServer(), member);
            if (npc != null && !npc.isRemoved()) {
                members.add(npc);
            }
        }
        return members;
    }

    /**
     * 共享索敌：逐个队员在各自 32 格里找最近的敌人，取全队最近的那个。
     * 用队员各自的位置而不是队长位置，站位拉开时也不会漏掉侧翼的怪。
     */
    private static Entity findSharedTarget(ServerLevel level, List<NpcEntity> members) {
        Entity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (NpcEntity npc : members) {
            if (!npc.isArmed()) {
                continue;
            }
            Entity candidate = ThreatIndex.nearest(level, npc.position(), SCAN_RANGE);
            if (!(candidate instanceof LivingEntity living) || living == npc || !npc.canAttack(living)) {
                continue;
            }
            double sqr = npc.distanceToSqr(living);
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = living;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 路线推进

    private static void driveRoute(ServerLevel level, RtsTeam team, Patrol patrol) {
        NpcEntity leader = RtsTeamManager.findAcrossLevels(level.getServer(), team.leaderUuid());
        if (leader == null || leader.isRemoved()) {
            return;
        }
        Vec3 here = leader.position();
        BlockPos node = patrol.node();
        if (!patrol.skipColumnWait && columnTooLoose(team, leader, patrol)) {
            // 纵队被甩开：队长原地等，等收拢到 COLUMN_RESUME_GAP 再继续；等太久就放弃等待，别把路线拖死
            if (++patrol.waitTicks > COLUMN_WAIT_LIMIT) {
                patrol.waitTicks = 0;
                patrol.waitingColumn = false;
                patrol.skipColumnWait = true;
            } else {
                if (!patrol.waitingColumn) {
                    patrol.waitingColumn = true;
                    patrol.issuedLeg = Long.MIN_VALUE;
                }
                leader.cancelRtsOrder();
                leader.getNavigation().stop();
                return;
            }
        }
        if (patrol.waitingColumn) {
            patrol.waitingColumn = false;
            patrol.waitTicks = 0;
            patrol.issuedLeg = Long.MIN_VALUE;
        }
        if (withinArrive(here, node)) {
            patrol.stallTicks = 0;
            patrol.skipColumnWait = false;
            patrol.waitTicks = 0;
            // 节点挨得比抵达半径还近时连续跳点，否则会在两点之间"原地来回"
            advanceFrom(here, patrol);
            RtsTeamManager.clearStands(team.teamId());
            persistProgress(level, team, patrol);
            long key = patrol.legKey();
            patrol.issuedLeg = key;
            BlockPos next = patrol.node();
            if (!withinArrive(here, next)) {
                issueLeg(leader, patrol, key, next);
            }
            return;
        }
        if (++patrol.stallTicks > STALL_TICKS) {
            // 节点不可达（被墙挡死等）：缓存路线可能已经过时，丢掉重算，然后跳过这个节点
            patrol.stallTicks = 0;
            patrol.skipColumnWait = false;
            patrol.waitTicks = 0;
            patrol.legs.clear();
            advanceFrom(here, patrol);
            persistProgress(level, team, patrol);
            patrol.issuedLeg = patrol.legKey();
            issueLeg(leader, patrol, patrol.issuedLeg, patrol.node());
            return;
        }
        long key = patrol.legKey();
        if (key != patrol.issuedLeg) {
            patrol.issuedLeg = key;
            issueLeg(leader, patrol, key, node);
            return;
        }
        if (!leader.hasRtsOrder() && level.getGameTime() % REISSUE_TICKS == 0) {
            leader.setRtsTarget(node, false);
        }
        captureLeg(leader, patrol, key, node);
    }

    /** 有缓存就直接套用缓存路点（跳过寻路搜索），没有就让队长正常寻路。 */
    private static void issueLeg(NpcEntity leader, Patrol patrol, long key, BlockPos node) {
        List<NpcPathfinder.Waypoint> cached = patrol.legs.get(key);
        if (cached != null) {
            List<NpcPathfinder.Waypoint> trimmed = trimToNearest(leader.position(), cached);
            if (trimmed != null) {
                leader.setRtsCachedPath(node, trimmed);
                return;
            }
            patrol.legs.remove(key);
        }
        leader.setRtsTarget(node, false);
    }

    /**
     * 纵队是否被队长甩得太开：用「离自己该站的位置最远的那个队员」判断。
     * 带队等待时用更小的收拢阈值，避免队长走一步停一步。
     */
    private static boolean columnTooLoose(RtsTeam team, NpcEntity leader, Patrol patrol) {
        double limit = patrol.waitingColumn ? COLUMN_RESUME_GAP : COLUMN_MAX_GAP;
        double limitSqr = limit * limit;
        for (UUID member : team.members()) {
            BlockPos stand = patrolStand(team, leader, member);
            if (stand == null) {
                continue;
            }
            NpcEntity npc = RtsTeamManager.findAcrossLevels(leader.level().getServer(), member);
            if (npc == null || npc.isRemoved()) {
                continue;
            }
            if (npc.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D) > limitSqr) {
                return true;
            }
        }
        return false;
    }

    /** 队长这段路第一次走通后把路点存下来，之后每一圈都直接复用。 */
    private static void captureLeg(NpcEntity leader, Patrol patrol, long key, BlockPos node) {
        if (patrol.legs.containsKey(key)) {
            return;
        }
        List<NpcPathfinder.Waypoint> path = leader.currentPath();
        if (path.size() < 2) {
            return;
        }
        if (!path.get(path.size() - 1).pos().equals(node)) {
            return;
        }
        patrol.legs.put(key, path);
    }

    /**
     * 缓存路径是从上一圈的起点算出来的：找到离队长最近的路点，从那里开始走，避免先倒回去几步。
     * 离得太远说明队长被打飞/传送了，缓存作废。
     */
    private static List<NpcPathfinder.Waypoint> trimToNearest(Vec3 here,
                                                              List<NpcPathfinder.Waypoint> path) {
        int best = 0;
        double bestSqr = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double dx = here.x - (path.get(i).pos().getX() + 0.5D);
            double dy = here.y - path.get(i).pos().getY();
            double dz = here.z - (path.get(i).pos().getZ() + 0.5D);
            double sqr = dx * dx + dy * dy + dz * dz;
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = i;
            }
        }
        if (bestSqr > LEG_CACHE_MAX_OFFSET * LEG_CACHE_MAX_OFFSET) {
            return null;
        }
        int from = Math.max(0, best - 1);
        List<NpcPathfinder.Waypoint> sliced = path.subList(from, path.size());
        return sliced.size() >= 2 ? sliced : null;
    }

    private static void persistProgress(ServerLevel level, RtsTeam team, Patrol patrol) {
        team.setPatrolProgress(patrol.index, patrol.direction);
        if (level != null && level.getServer() != null) {
            RtsTeamStorage.savePatrol(level.getServer(), team);
        }
    }

    private static boolean withinArrive(Vec3 pos, BlockPos node) {
        return horizontalSqr(pos, node) <= NODE_ARRIVE * NODE_ARRIVE
                && Math.abs(pos.y - node.getY()) <= NODE_ARRIVE_Y;
    }

    /** 沿当前方向前进节点，直到找到一个「当前位置还没到」的节点；整条路线都在半径内时最多绕一圈。 */
    private static void advanceFrom(Vec3 pos, Patrol patrol) {
        for (int guard = 0; guard < patrol.route.size(); guard++) {
            patrol.step();
            if (!withinArrive(pos, patrol.node())) {
                return;
            }
        }
    }

    private static void issueNode(ServerLevel level, RtsTeam team, Patrol patrol) {
        NpcEntity leader = RtsTeamManager.findAcrossLevels(level.getServer(), team.leaderUuid());
        if (leader == null || leader.isRemoved()) {
            return;
        }
        long key = patrol.legKey();
        patrol.issuedLeg = key;
        issueLeg(leader, patrol, key, patrol.node());
    }

    private static double horizontalSqr(Vec3 point, BlockPos node) {
        double dx = point.x - (node.getX() + 0.5D);
        double dz = point.z - (node.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ 巡逻阵型

    /**
     * 巡逻期间的临时站位：队长领头，队员排成 3 列纵队跟在他身后，行数按人数自动增长，
     * 队长比第一排再前 2 格。
     * <p>
     * 这里只算位置，不动 {@link RtsTeam} 里保存的阵型偏移，所以巡逻一结束、
     * {@link RtsTeamManager#tick} 重新按原偏移站位时，阵型自然就恢复了。
     *
     * @return null 表示这人不用管（队长本人或不在队伍里）
     */
    public static BlockPos patrolStand(RtsTeam team, NpcEntity leader, UUID member) {
        if (team == null || leader == null || member == null) {
            return null;
        }
        if (member.equals(team.leaderUuid())) {
            return null;
        }
        int slot = team.patrolSlot(member);
        if (slot < 0) {
            return null;
        }
        Vec3 forward = travelDirection(team.teamId(), leader);
        Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
        int column = slot % COLUMNS;
        int row = slot / COLUMNS;
        double lateral = (column - (COLUMNS - 1) / 2.0D) * COL_SPACING;
        double back = LEADER_LEAD + row * ROW_SPACING;
        Vec3 stand = leader.position()
                .subtract(forward.scale(back))
                .add(side.scale(lateral));
        return BlockPos.containing(stand.x, leader.getY(), stand.z);
    }

    /** 行进方向 = 队长指向当前巡逻节点；贴脸时退化成队长朝向。 */
    private static Vec3 travelDirection(UUID teamId, NpcEntity leader) {
        BlockPos node = nodeOf(teamId);
        if (node != null) {
            double dx = node.getX() + 0.5D - leader.getX();
            double dz = node.getZ() + 0.5D - leader.getZ();
            double lengthSqr = dx * dx + dz * dz;
            if (lengthSqr > 1.0D) {
                double length = Math.sqrt(lengthSqr);
                return new Vec3(dx / length, 0.0D, dz / length);
            }
        }
        double yaw = Math.toRadians(leader.getYRot());
        return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 玩家显式作战命令（坚守 / 进攻 / 红框指定目标）落到某个队员身上时，
     * 先撤掉它所属队伍的巡逻，避免「巡逻服务继续推进路线」和「玩家命令」互相覆盖。
     */
    public static void cancelForMember(ServerLevel level, UUID memberId) {
        UUID teamId = RtsTeamManager.teamIdOf(memberId);
        if (teamId == null) {
            return;
        }
        if (interrupt(level, teamId)) {
            RtsTeam team = RtsTeamManager.get(teamId);
            if (team != null) {
                RtsTeamService.syncToOwner(level, team);
            }
        }
    }

    public static boolean isPatrolling(UUID teamId) {
        return teamId != null && PATROLS.containsKey(teamId);
    }

    public static boolean isEngaged(UUID teamId) {
        Patrol patrol = teamId == null ? null : PATROLS.get(teamId);
        return patrol != null && patrol.engaged;
    }

    /** 当前巡逻节点：战斗控制器拿它当追击圆心的锚点。 */
    public static BlockPos nodeOf(UUID teamId) {
        Patrol patrol = teamId == null ? null : PATROLS.get(teamId);
        return patrol == null ? null : patrol.node();
    }

    public static void clearAll() {
        PATROLS.clear();
    }
}
