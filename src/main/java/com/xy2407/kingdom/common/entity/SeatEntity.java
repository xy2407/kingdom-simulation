package com.xy2407.kingdom.common.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 座位实体：把乘客固定在椅子座面上并强制坐姿，无渲染无碰撞，无人即自毁
 */
public class SeatEntity extends Entity {

    public SeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
    }

    public static SeatEntity create(Level level, double x, double y, double z) {
        SeatEntity seat = ModEntities.SEAT.get().create(level);
        if (seat == null) {
            return null;
        }
        seat.setPos(x, y, z);
        return seat;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions,
                                               float partialTick) {
        return Vec3.ZERO;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return new Vec3(getX(), getY() + 1.0D, getZ());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (getPassengers().isEmpty()) {
            if (!level().isClientSide) {
                discard();
            }
            return;
        }
        if (getPassengers().get(0) instanceof Player player && player.getForcedPose() != Pose.SITTING) {
            player.setForcedPose(Pose.SITTING);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (passenger instanceof Player player) {
            player.setForcedPose(null);
        }
        if (!level().isClientSide) {
            discard();
        }
    }
}
