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
 * RTS 期间的玩家替身：一个用玩家皮肤渲染的"假人"，作为世界里的可选中单位。
 *
 * <p>进入 RTS 时由服务端在玩家脚下生成，相机与玩家本体分离，玩家控制的是这个假人；
 * 退出时假人被回收，玩家本体被放到假人最后站的位置。它自己驱动自己（{@code getNavigation().moveTo}），
 * 不依赖任何外部任务表。</p>
 *
 * <p>移植自 {@code xy's_nsuk} 的 {@code RtsFakePlayerEntity}，但按"基础移动"做了减法：
 * 去掉了门开合、游泳、坐骑、Sable 结构读取，寻路改用原版 {@code PathNavigation}
 * （nsuk 那份自带 {@code FakePlayerPathfinder}）。</p>
 */
public class RtsFakePlayerEntity extends PathfinderMob {

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(RtsFakePlayerEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 到这个距离内算到达。 */
    private static final double ARRIVE_DISTANCE = 0.9D;
    /** 每隔多少 tick 重新下发一次导航目标。 */
    private static final int REPATH_INTERVAL = 20;
    /** 连续这么多 tick 几乎没动就判定卡住，放弃当前目标。 */
    private static final int STUCK_LIMIT = 100;

    private Vec3 targetPos;
    private int repathCooldown;
    private int stuckTicks;
    private Vec3 lastTickPos;

    /** 只留这一个构造器：参数是实体注册用的精确类型，避免 EntityType.Builder 推断不出 T。 */
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

    /**
     * 命令它走到某个位置；传 {@code null} 就是停下。
     *
     * <p>名字避开 {@code setTarget}/{@code getTarget}：{@code Mob} 已经有
     * {@code setTarget(LivingEntity)}，而 {@code getTarget()} 是 {@code Targeting} 接口的方法
     * （返回类型必须是 {@code LivingEntity}），同名同参数会直接编译不过。</p>
     */
    public void setMoveTarget(Vec3 target) {
        targetPos = target;
        repathCooldown = 0;
        stuckTicks = 0;
        lastTickPos = null;
    }

    public Vec3 getMoveTarget() {
        return targetPos;
    }

    // ------------------------------------------------------------------
    // 每 tick 自己走
    // ------------------------------------------------------------------

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
                // 走不到就放弃，停在原地，别一直贴着墙磨。
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

    // ------------------------------------------------------------------
    // 行为约束：不推人、不被推动、不掉血、不自然消失
    // ------------------------------------------------------------------

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

    /** 让原版 AI 完全让位：移动只由 {@link #setMoveTarget} 驱动。 */
    @Override
    protected void registerGoals() {
    }

    // ------------------------------------------------------------------
    // 存档：只记归属与目标点（本体是瞬态实体，重启后不需要恢复）
    // ------------------------------------------------------------------

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

    /** 便于调试：当前目标点所在的方块。 */
    public BlockPos targetBlock() {
        return targetPos == null ? null : BlockPos.containing(targetPos);
    }
}
