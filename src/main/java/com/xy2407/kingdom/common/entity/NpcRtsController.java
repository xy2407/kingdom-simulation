package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.core.BlockPos;

/**
 * RTS 移动指令：整队目标、路点推进、到达判定与失控放弃。
 */
final class NpcRtsController {

    private static final double RTS_ARRIVE_SQR = 4.0D;
    private static final double CROWD_ARRIVE_SQR = 2.25D;
    private static final double GROUP_HANDOFF_SQR = 9.0D;
    private static final double ARRIVE_HEIGHT = 1.0D;
    private static final int GIVE_UP_TICKS = 600;

    private final NpcEntity npc;
    private final ArrayDeque<BlockPos> waypoints = new ArrayDeque<>();
    private BlockPos target;
    private boolean clearOnArrive;
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
        apply(target, clearOnArrive);
    }

    void setGroupTarget(BlockPos landing, BlockPos groupTarget, boolean clearOnArrive) {
        waypoints.clear();
        groupDest = groupTarget;
        stuckTicks = 0;
        stuckAnchor = null;
        npc.resetSharedPathKey();
        apply(landing, clearOnArrive);
    }

    void setWaypoints(List<BlockPos> route) {
        waypoints.clear();
        if (route == null || route.isEmpty()) {
            return;
        }
        waypoints.addAll(route);
        target = waypoints.peekLast();
        clearOnArrive = false;
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

    BlockPos groupDest() {
        return groupDest;
    }

    void clearGroupDest() {
        groupDest = null;
    }

    double arrivalThreshold() {
        return target == null ? 1.0D : RTS_ARRIVE_SQR;
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
        waypoints.clear();
        groupDest = null;
        npc.resetSharedPathKey();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(false);
        }
    }

    void tick() {
        BlockPos current = target;
        if (current == null) {
            npc.stopSitting();
            npc.getNavigation().stop();
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
            npc.getNavigation().stop();
            release();
            return;
        }
        advance();
        BlockPos segment = waypoints.isEmpty() ? current : waypoints.peekFirst();
        if (npc.tickPathTo(segment, false)) {
            npc.getNavigation().stop();
        }
    }

    private void apply(BlockPos destination, boolean clear) {
        target = destination;
        clearOnArrive = clear;
        npc.stopConstructionPath();
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        npc.setNavTarget(destination);
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
            npc.resetSharedPathKey();
            npc.stopConstructionPath();
        }
    }

    private boolean isArrived(BlockPos destination) {
        double dx = npc.getX() - (destination.getX() + 0.5D);
        double dz = npc.getZ() - (destination.getZ() + 0.5D);
        return dx * dx + dz * dz < RTS_ARRIVE_SQR
                && Math.abs(npc.getY() - destination.getY()) < ARRIVE_HEIGHT;
    }

}
