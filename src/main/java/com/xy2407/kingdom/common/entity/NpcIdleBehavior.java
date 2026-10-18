package com.xy2407.kingdom.common.entity;

import java.util.List;

import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcDetail;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * 空闲行为：闲逛、社交、原地坐下，只读位置与日程状态。
 */
final class NpcIdleBehavior {

    private static final double WANDER_RADIUS = 10.0D;
    private static final double SIT_WALK_RADIUS = 4.0D;
    private static final double SOCIALIZE_RADIUS = 12.0D;
    private static final double SOCIALIZE_GAP = 1.0D;
    private static final double WALK_SPEED = 0.5D;
    private static final double ARRIVE_SQR = 1.0D;

    private final NpcEntity npc;
    private boolean sitWalking;
    private boolean socialized;
    private NpcBrain.WanderStyle activeStyle;
    private NpcEntity socialPartner;

    NpcIdleBehavior(NpcEntity npc) {
        this.npc = npc;
    }

    void tick(NpcBrain brain) {
        NpcBrain.WanderStyle style = brain.getWanderStyle();
        if (style != activeStyle) {
            activeStyle = style;
            sitWalking = false;
            socialized = false;
            clearSocialPartner();
            npc.getNavigation().stop();
            npc.clearNavTarget();
        }
        switch (style) {
            case SOCIALIZE -> socialize();
            case SIT -> sitBehavior();
            default -> {
                BlockPos nav = npc.getNavTarget();
                if (nav == null || npc.nearTarget(nav, ARRIVE_SQR)) {
                    walkToRandomPoint(npc.blockPosition(), WANDER_RADIUS);
                }
            }
        }
        if (style != NpcBrain.WanderStyle.SIT) {
            stopSitting();
        }
    }

    void wanderAround(int x, int y, int z) {
        BlockPos target = new BlockPos(x, y, z);
        BlockPos nav = npc.getNavTarget();
        if (nav == null || npc.nearTarget(nav, ARRIVE_SQR)) {
            walkToRandomPoint(target, WANDER_RADIUS);
        }
    }

    boolean walkToRandomPoint(BlockPos center, double radius) {
        for (int i = 0; i < 8; i++) {
            double angle = npc.randomSource().nextDouble() * Math.PI * 2.0D;
            double dist = npc.randomSource().nextDouble() * radius;
            double tx = center.getX() + 0.5D + Math.cos(angle) * dist;
            double tz = center.getZ() + 0.5D + Math.sin(angle) * dist;
            BlockPos target = BlockPos.containing(tx, center.getY(), tz);
            if (npc.getNavigation().isStableDestination(target)) {
                npc.getNavigation().moveTo(tx, center.getY(), tz, WALK_SPEED);
                npc.setNavTarget(target);
                return true;
            }
        }
        return false;
    }

    void clearSocialPartner() {
        NpcEntity partner = socialPartner;
        socialPartner = null;
        if (partner != null && partner.idle().socialPartner == npc) {
            partner.idle().socialPartner = null;
        }
    }

    void reset() {
        sitWalking = false;
        socialized = false;
        activeStyle = null;
        clearSocialPartner();
    }

    void stopSitting() {
        if (npc.isSitting()) {
            npc.setSitting(false);
        }
    }

    private void socialize() {
        NpcEntity partner = socialPartner;
        if (partner == null || !isSocialPartner(partner)) {
            clearSocialPartner();
            socialized = false;
            return;
        }
        double gap = SOCIALIZE_GAP + 0.4D;
        if (npc.distanceToSqr(partner) > gap * gap) {
            socialized = false;
            if (!npc.getNavigation().isInProgress()) {
                double dx = npc.getX() - partner.getX();
                double dz = npc.getZ() - partner.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                double tx = partner.getX() + (len > 1.0E-4 ? dx / len * SOCIALIZE_GAP : SOCIALIZE_GAP);
                double tz = partner.getZ() + (len > 1.0E-4 ? dz / len * SOCIALIZE_GAP : SOCIALIZE_GAP);
                npc.getNavigation().moveTo(tx, npc.getY(), tz, WALK_SPEED);
            }
        } else {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(partner, 30.0F, 30.0F);
            if (!socialized) {
                socialized = true;
                spawnConversationParticle();
            }
        }
    }

    private boolean isSocialPartner(NpcEntity other) {
        if (other == npc || !other.isAlive() || other.getCitizenId() == null) {
            return false;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(other.getCitizenId());
        if (data == null) {
            return false;
        }
        NpcBrain brain = data.getBrain();
        return brain.getDetail() == NpcDetail.WANDERING
                && brain.getWanderStyle() == NpcBrain.WanderStyle.SOCIALIZE;
    }

    private void spawnConversationParticle() {
        if (!(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ParticleOptions particle = npc.randomSource().nextBoolean()
                ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER;
        serverLevel.sendParticles(particle, npc.getX(), npc.getY() + npc.getEyeHeight(), npc.getZ(),
                6, 0.3D, 0.3D, 0.3D, 0.0D);
    }

    private void sitBehavior() {
        if (npc.isSitting()) {
            npc.getNavigation().stop();
            return;
        }
        if (!sitWalking) {
            sitWalking = true;
            walkToRandomPoint(npc.blockPosition(), SIT_WALK_RADIUS);
        } else if (npc.getNavigation().isDone()) {
            sitWalking = false;
            npc.setSitting(true);
        }
    }
}
