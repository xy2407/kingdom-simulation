package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.path.NpcPathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * RTS 移动指令：整队目标、路点推进、到达判定与卡死传送到下一节点。
 */
final class NpcRtsController {

    /** 普通 RTS 移动的抵达半径：保持原样（3 格），不要动。 */
    private static final double RTS_ARRIVE_SQR = 9.0D;
    /** 精确模式（阵型编辑）的抵达半径：严格 0.1 格（0.1² = 0.01）。 */
    private static final double PRECISE_ARRIVE_SQR = 0.01D;
    /** 精确模式（阵型编辑）最后一个路点的推进半径（格）。 */
    private static final double PRECISE_LAST_HOP = 0.1D;
    private static final double PRECISE_HEIGHT = 0.75D;
    private static final double CROWD_ARRIVE_SQR = 9.0D;
    private static final double GROUP_HANDOFF_SQR = 9.0D;
    private static final double ARRIVE_HEIGHT = 1.5D;
    private static final int GIVE_UP_TICKS = 600;

    private final NpcEntity npc;
    private final ArrayDeque<BlockPos> waypoints = new ArrayDeque<>();
    private BlockPos target;
    private boolean clearOnArrive;
    private boolean precise;
    /**
     * 指令是「软指令」：队伍跟随、阵型这类自动下发的站位，战斗追击可以打断它。
     * 玩家手动下达的移动指令是「硬指令」，战斗一律让路（行军途中只在贴身时还手）。
     */
    private boolean softOrder;
    /** 玩家硬指令走紧急寻路通道（独立配额+线程池），不被全城日常寻路挤掉。 */
    private boolean urgentOrder;
    private BlockPos groupDest;
    private int stuckTicks;
    private BlockPos stuckAnchor;

    NpcRtsController(NpcEntity npc) {
        this.npc = npc;
    }

    void setTarget(BlockPos target, boolean clearOnArrive) {
        waypoints.clear();
        groupDest = null;
        stuckTicks = 0;
        stuckAnchor = null;
        npc.resetSharedPathKey();
        apply(target, clearOnArrive, true, false);
    }

    void setGroupTarget(BlockPos landing, BlockPos groupTarget, boolean clearOnArrive) {
        waypoints.clear();
        groupDest = groupTarget;
        stuckTicks = 0;
        stuckAnchor = null;
        npc.resetSharedPathKey();
        apply(landing, clearOnArrive, false, true);
    }

    /**
     * 预计算路点直接驱动：语义同 {@link #setTarget}，但不提交寻路搜索。
     * 巡逻的每段路线走通一次后就会走这里，省掉每一圈的 A*。
     */
    void setCachedPath(BlockPos destination, List<NpcPathfinder.Waypoint> path) {
        if (destination == null || path == null || path.size() < 2) {
            setTarget(destination, false);
            return;
        }
        waypoints.clear();
        groupDest = null;
        stuckTicks = 0;
        stuckAnchor = null;
        target = destination;
        clearOnArrive = false;
        softOrder = true;
        urgentOrder = false;
        npc.resetSharedPathKey();
        npc.stopConstructionPath();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        npc.setNavTarget(destination);
        npc.applyPath(path);
    }

    void setWaypoints(List<BlockPos> route) {
        waypoints.clear();
        if (route == null || route.isEmpty()) {
            return;
        }
        waypoints.addAll(route);
        target = waypoints.peekLast();
        clearOnArrive = false;
        softOrder = false;
        urgentOrder = false;
        groupDest = null;
        stuckTicks = 0;
        stuckAnchor = null;
        npc.resetSharedPathKey();
        npc.stopConstructionPath();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        npc.setNavTarget(target);
    }

    List<BlockPos> waypoints() {
        return new ArrayList<>(waypoints);
    }

    BlockPos target() {
        return target;
    }

    boolean hasOrder() {
        return target != null;
    }

    BlockPos groupDest() {
        return groupDest;
    }

    void clearGroupDest() {
        groupDest = null;
    }

    double arrivalThreshold() {
        if (target == null) {
            return 1.0D;
        }
        return precise ? PRECISE_ARRIVE_SQR : RTS_ARRIVE_SQR;
    }

    void setPrecise(boolean value) {
        precise = value;
    }

    boolean withinHandoff(BlockPos pos) {
        return groupDest != null && pos.distSqr(groupDest) < GROUP_HANDOFF_SQR;
    }

    void clearWaypoints() {
        waypoints.clear();
    }

    void clear() {
        target = null;
        groupDest = null;
        waypoints.clear();
    }

    void release() {
        target = null;
        softOrder = false;
        urgentOrder = false;
        waypoints.clear();
        groupDest = null;
        npc.resetSharedPathKey();
        // 释放指令时把路径清干净并停一次导航。以前清在这里、tick 里每帧再 stop 一次，
        // 结果战斗追击（同样走 tickPathTo）每帧刚被驱动就被掐断，人只会站着转头。
        npc.stopConstructionPath();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(false);
        }
    }

    void tick() {
        BlockPos current = target;
        if (current == null) {
            npc.stopSitting();
            return;
        }
        if (isArrived(current)) {
            waypoints.clear();
            if (clearOnArrive) {
                npc.discard();
                return;
            }
            npc.stopSitting();
            npc.getNavigation().stop();
            release();
            return;
        }
        npc.stopSitting();
        BlockPos now = npc.blockPosition();
        if (stuckAnchor == null || !stuckAnchor.equals(now)) {
            stuckAnchor = now;
            stuckTicks = 0;
        } else if (++stuckTicks >= GIVE_UP_TICKS) {
            stuckAnchor = now;
            stuckTicks = 0;
            resetStuck();
            return;
        }
        advance();
        BlockPos segment = waypoints.isEmpty() ? current : waypoints.peekFirst();
        npc.tickPathTo(segment, false);
    }

    /**
     * RTS 移动指令卡住：只清运动状态让下一 tick 重新寻路，绝不传送（否则等于玩家直接瞬移军队）
     */
    private void resetStuck() {
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        npc.stopConstructionPath();
        stuckAnchor = null;
        stuckTicks = 0;
    }

    private void apply(BlockPos destination, boolean clear, boolean soft, boolean urgent) {
        target = destination;
        clearOnArrive = clear;
        softOrder = soft;
        urgentOrder = urgent;
        npc.stopConstructionPath();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        npc.setNavTarget(destination);
        // 移动启动：当 tick 就提交寻路。以前 setNavTarget 只记字段，真正提交要等
        // brain 重评后的下一拍 rts.tick，中间 detail 分支的寻路还会插队抢工作区。
        npc.tickPathTo(destination, false);
    }

    private void advance() {
        BlockPos node = waypoints.peekFirst();
        if (node == null) {
            return;
        }
        double dx = npc.getX() - (node.getX() + 0.5D);
        double dz = npc.getZ() - (node.getZ() + 0.5D);
        if (dx * dx + dz * dz < CROWD_ARRIVE_SQR
                && Math.abs(npc.getY() - node.getY()) < ARRIVE_HEIGHT) {
            waypoints.poll();
        }
    }

    private boolean isArrived(BlockPos destination) {
        double dx = npc.getX() - (destination.getX() + 0.5D);
        double dz = npc.getZ() - (destination.getZ() + 0.5D);
        double threshold = precise ? PRECISE_ARRIVE_SQR : RTS_ARRIVE_SQR;
        double height = precise ? PRECISE_HEIGHT : ARRIVE_HEIGHT;
        return dx * dx + dz * dz < threshold
                && Math.abs(npc.getY() - destination.getY()) < height;
    }

    /** 是否处于阵型编辑的精确抵达模式。 */
    boolean isPrecise() {
        return precise;
    }

    /** 当前指令是「软指令」（队伍跟随/阵型站位），可被战斗追击打断。 */
    boolean isSoftOrder() {
        return softOrder;
    }

    /** 当前指令是否为玩家硬指令（走紧急寻路通道）。 */
    boolean isUrgentOrder() {
        return urgentOrder;
    }

    /**
     * 最后一段路点的推进半径（格）。
     * 只有精确模式（阵型编辑）会用到：0.1 格。普通 RTS 根本不走这个方法。
     */
    double lastHopRadius() {
        return PRECISE_LAST_HOP;
    }

}
