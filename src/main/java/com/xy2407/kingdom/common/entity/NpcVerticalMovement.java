package com.xy2407.kingdom.common.entity;

import com.xy2407.kingdom.common.path.NpcPathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

/**
 * NPC 垂直移动执行：攀爬速度、台阶起跳与可攀爬/下行判定。
 */
final class NpcVerticalMovement {

    private static final int CLIMB_INTERVAL_TICKS = 10;

    private final NpcEntity npc;
    private boolean climbState;
    private int climbStateTick = -1;
    private int climbCooldown;

    NpcVerticalMovement(NpcEntity npc) {
        this.npc = npc;
    }

    boolean onClimbable() {
        int now = npc.tickCount;
        if (climbStateTick == now) {
            return climbState;
        }
        climbStateTick = now;
        BlockPos pos = npc.blockPosition();
        climbState = npc.level().getBlockState(pos).is(BlockTags.CLIMBABLE)
                || npc.level().getBlockState(pos.below()).is(BlockTags.CLIMBABLE);
        return climbState;
    }

    boolean isDescending(int nextY) {
        if (nextY == Integer.MIN_VALUE || !onClimbable()) {
            return false;
        }
        return nextY < npc.getBlockY();
    }

    void applyVertical(NpcPathfinder.Waypoint waypoint, double climbDirection) {
        int dy = waypoint.pos().getY() - npc.getBlockY();
        if (onClimbable() && Math.abs(dy) >= 1) {
            climbMove(dy > 0 ? 1.0D : -1.0D);
            return;
        }
        switch (waypoint.move()) {
            case STEP_UP -> {
                if (npc.onGround() && climbCooldown <= 0 && npc.getDeltaMovement().y <= 0.02D) {
                    climbCooldown = CLIMB_INTERVAL_TICKS;
                    npc.getJumpControl().jump();
                }
            }
            case CLIMB -> climbMove(climbDirection);
            default -> {
            }
        }
    }

    void climbMove(double climbDirection) {
        Vec3 movement = npc.getDeltaMovement();
        if (climbDirection > 0) {
            npc.setDeltaMovement(movement.x * 0.5D, 0.2D, movement.z * 0.5D);
        } else if (climbDirection < 0) {
            npc.setDeltaMovement(movement.x * 0.5D, -0.2D, movement.z * 0.5D);
        }
        npc.fallDistance = 0.0F;
    }

    void tickCooldown() {
        if (climbCooldown > 0) {
            climbCooldown--;
        }
    }

    void resetCooldown() {
        climbCooldown = 0;
    }
}
