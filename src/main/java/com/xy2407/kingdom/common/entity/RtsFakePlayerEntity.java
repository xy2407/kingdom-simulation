package com.xy2407.kingdom.common.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * RTS 期间的玩家替身：用玩家皮肤渲染的假人，作为世界里的可选中单位。
 */
public class RtsFakePlayerEntity extends PathfinderMob {

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(RtsFakePlayerEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final double ARRIVE_DISTANCE = 0.9D;
    private static final int REPATH_INTERVAL = 20;
    private static final int STUCK_LIMIT = 100;

    private Vec3 targetPos;
    private int repathCooldown;
    private int stuckTicks;
    private Vec3 lastTickPos;

    public RtsFakePlayerEntity(EntityType<RtsFakePlayerEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.32D)
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    public UUID getOwnerUUID() {
        return getEntityData().get(DATA_OWNER).orElse(null);
    }

    public void setOwnerUUID(UUID owner) {
        getEntityData().set(DATA_OWNER, Optional.ofNullable(owner));
    }

    public void setMoveTarget(Vec3 target) {
        targetPos = target;
        repathCooldown = 0;
        stuckTicks = 0;
        lastTickPos = null;
    }

    public Vec3 getMoveTarget() {
        return targetPos;
    }


    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        Vec3 target = targetPos;
        if (target == null) {
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
            return;
        }

        if (horizontalDistSqr(position(), target) < ARRIVE_DISTANCE * ARRIVE_DISTANCE) {
            arrive(target);
            return;
        }

        if (--repathCooldown <= 0) {
            getNavigation().moveTo(target.x, target.y, target.z, 1.0D);
            repathCooldown = REPATH_INTERVAL;
        }

        Vec3 now = position();
        if (lastTickPos == null) {
            lastTickPos = now;
            return;
        }
        double moved = now.distanceTo(lastTickPos);
        lastTickPos = now;
        if (moved < 0.02D) {
            if (++stuckTicks > STUCK_LIMIT) {
                setMoveTarget(null);
                getNavigation().stop();
                setDeltaMovement(Vec3.ZERO);
            }
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }
    }

    private void arrive(Vec3 target) {
        targetPos = null;
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        setPos(Math.floor(target.x) + 0.5D, getY(), Math.floor(target.z) + 0.5D);
    }

    private static double horizontalDistSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }


    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected void registerGoals() {
    }


    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("KingdomOwner", owner);
        }
        if (targetPos != null) {
            tag.putDouble("KingdomTargetX", targetPos.x);
            tag.putDouble("KingdomTargetY", targetPos.y);
            tag.putDouble("KingdomTargetZ", targetPos.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("KingdomOwner")) {
            setOwnerUUID(tag.getUUID("KingdomOwner"));
        }
        if (tag.contains("KingdomTargetX")) {
            targetPos = new Vec3(tag.getDouble("KingdomTargetX"),
                    tag.getDouble("KingdomTargetY"), tag.getDouble("KingdomTargetZ"));
        }
    }

    public BlockPos targetBlock() {
        return targetPos == null ? null : BlockPos.containing(targetPos);
    }
}
