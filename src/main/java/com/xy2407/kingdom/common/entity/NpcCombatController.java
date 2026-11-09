package com.xy2407.kingdom.common.entity;

import java.util.UUID;

import com.xy2407.kingdom.common.combat.CombatWeapons;
import com.xy2407.kingdom.common.npc.NpcDetail;
import com.xy2407.kingdom.server.combat.ThreatIndex;
import com.xy2407.kingdom.server.rts.RtsPatrolService;
import com.xy2407.kingdom.server.rts.RtsTeamManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 战斗控制器：坚守模式下按近战与远程分别执行原地被动防守
 */
final class NpcCombatController {

    enum CombatMode {
        STANDBY,
        PATROL,
        HOLD,
        ATTACK
    }

    private static final double MELEE_REACH = 3.0D;
    private static final double MELEE_MOVE_SCAN = MELEE_REACH + 1.5D;
    private static final double ATTACK_SCAN_RANGE = 24.0D;
    private static final double RANGED_RANGE = 100.0D;
    private static final double RANGED_APPROACH_RANGE = 64.0D;
    private static final double RANGED_SAFE_RANGE = 30.0D;
    private static final double RANGED_RETREAT_STEP = 6.0D;
    private static final double RANGED_MIN_RANGE = 2.0D;
    private static final double MAX_VERTICAL_CHASE = 12.0D;
    private static final int CHASE_GIVE_UP_TICKS = 600;
    /** 巡逻交战的追击上限：再久就放弃，回到巡逻路线（防止追丢）。 */
    private static final int PATROL_CHASE_TICKS = 120;
    private static final double TARGET_LEASH = 1.5D;
    private static final int MELEE_COOLDOWN = 20;
    private static final int BOW_COOLDOWN = 24;
    private static final int CROSSBOW_COOLDOWN = 30;
    private static final int SCAN_INTERVAL = 10;
    private static final int LOS_FAIL_LIMIT = 60;
    /** 原版满蓄力弓 3.0（BowItem: power*3.0）、弩 3.15（CrossbowItem.ARROW_POWER）。 */
    private static final double ARROW_SPEED_BOW = 3.0D;
    private static final double ARROW_SPEED_CROSSBOW = 3.15D;
    /**
     * 原版 {@code AbstractArrow.tick} 的实际顺序：先 {@code p += v}，再 {@code v = 0.99v − 0.05ŷ}
     * （{@code getDefaultGravity() = 0.05}）。所以 T tick 的竖直位移是
     * {@code vy·G(T) − 5·(T − G(T))}，其中 {@code G(T) = (1 − 0.99^T)/0.01}，
     * 重力系数固定为 {@code g/(1−drag) = 5.0}。旧公式写成 {@code 0.05·G}，少算 10 倍。
     */
    private static final double ARROW_DRAG = 0.99D;
    private static final double ARROW_GRAVITY = 0.05D;
    private static final double GRAVITY_CARRY = ARROW_GRAVITY / (1.0D - ARROW_DRAG);
    private static final double HIT_RATE_TARGET = 0.7D;
    private static final double MIN_SPREAD_RADIUS = 0.25D;
    private static final double MAX_SPREAD_RADIUS = 1.2D;
    /** 箭矢出射高度占身高的比例：0.75 ≈ 1.35 格，正好是手/胸口的高度，不是腰以下。 */
    private static final double ARM_HEIGHT_FACTOR = 0.75D;

    private final NpcEntity npc;
    private CombatMode mode = CombatMode.STANDBY;
    private UUID attackTargetId;
    private BlockPos patrolPoint;
    private BlockPos holdPoint;
    private Entity target;
    private Entity assignedTarget;
    /** 巡逻共享目标：由 {@link RtsPatrolService} 广播给全队，优先级低于玩家 Ctrl 指定、高于反击与自动索敌。 */
    private Entity patrolTarget;
    /** 被打反击目标：优先于自动索敌，但低于玩家 Ctrl 指定的 assignedTarget。 */
    private Entity retaliateTarget;
    private int attackCooldown;
    private int scanCooldown;
    private int losFailTicks;
    private boolean wasMoving;
    private int chaseTicks;

    NpcCombatController(NpcEntity npc) {
        this.npc = npc;
    }

    void tick() {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        switch (mode) {
            case PATROL -> tickPatrol(level);
            case HOLD -> tickHold(level);
            case ATTACK -> tickAttack(level);
            default -> tickRetaliateOnly(level);
        }
    }

    /**
     * 巡逻态：不自己索敌，只打巡逻服务广播下来的共享目标。
     * 近战追上去砍（追击圆心是当前巡逻节点，超出 32 格立刻放手），远程原地停下射。
     * 没有共享目标时什么都不做——移动由巡逻服务驱动，两边不抢方向盘。
     */
    private void tickPatrol(ServerLevel level) {
        if (!npc.isArmed()) {
            clearAggro();
            return;
        }
        boolean ranged = CombatWeapons.isRanged(npc.weaponStack());
        // 近战举盾 / 远程收盾（原版右键盾的姿态），顺带把旧弓姿收掉
        npc.syncShieldPose(ranged);
        if (patrolTarget == null) {
            return;
        }
        target = patrolTarget;
        if (!patrolTargetValid(level)) {
            clearPatrolTarget();
            return;
        }
        if (ranged) {
            tickRanged(level);
        } else {
            tickPatrolMelee();
        }
        if (target != null) {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    /** 共享目标是否仍然有效：活着、可攻击、且没有跑出「当前巡逻节点 32 格」。 */
    private boolean patrolTargetValid(ServerLevel level) {
        if (patrolTarget == null) {
            return false;
        }
        if (patrolTarget.isRemoved() || !patrolTarget.isAlive() || patrolTarget.level() != level) {
            return false;
        }
        if (!(patrolTarget instanceof LivingEntity living) || !npc.canAttack(living)) {
            return false;
        }
        BlockPos node = patrolNode();
        if (node == null) {
            return true;
        }
        double dx = patrolTarget.getX() - (node.getX() + 0.5D);
        double dz = patrolTarget.getZ() - (node.getZ() + 0.5D);
        return dx * dx + dz * dz <= RtsPatrolService.SCAN_RANGE * RtsPatrolService.SCAN_RANGE;
    }

    private BlockPos patrolNode() {
        UUID teamId = RtsTeamManager.teamIdOf(npc.getUUID());
        return teamId == null ? null : RtsPatrolService.nodeOf(teamId);
    }

    private void tickPatrolMelee() {
        if (target == null) {
            return;
        }
        if (npc.getBoundingBox().inflate(MELEE_REACH).intersects(target.getBoundingBox())) {
            chaseTicks = 0;
            npc.getNavigation().stop();
            tickMelee();
            return;
        }
        if (Math.abs(npc.getY() - target.getY()) > MAX_VERTICAL_CHASE
                || ++chaseTicks > PATROL_CHASE_TICKS) {
            chaseTicks = 0;
            clearPatrolTarget();
            return;
        }
        npc.tickPathTo(BlockPos.containing(target.getBoundingBox().getCenter()), false);
    }

    /** 被打反击（待命态专用）：不追击、不主动索敌，只对动手的家伙还手。 */
    private void tickRetaliateOnly(ServerLevel level) {
        if (retaliateTarget == null) {
            return;
        }
        boolean ranged = CombatWeapons.isRanged(npc.weaponStack());
        npc.syncShieldPose(ranged);
        target = retaliateTarget;
        if (!isTargetValid(level, ranged ? RANGED_RANGE : MELEE_MOVE_SCAN)) {
            clearTarget();
            return;
        }
        if (ranged) {
            tickRanged(level);
        } else if (npc.getBoundingBox().inflate(MELEE_REACH).intersects(target.getBoundingBox())) {
            tickMelee();
        }
        if (target != null) {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private void tickAttack(ServerLevel level) {
        if (!npc.isArmed()) {
            clearAggro();
            wasMoving = false;
            return;
        }
        boolean ranged = CombatWeapons.isRanged(npc.weaponStack());
        // 换成远程就把盾放下、换成近战就举盾
        npc.syncShieldPose(ranged);
        refreshAssigned(level);
        // 先确认有没有敌人，再决定要不要被移动指令挡住
        boolean hasTarget = ensureTarget(level, ranged, ranged ? RANGED_RANGE : ATTACK_SCAN_RANGE);
        if (npc.hasRtsOrder()) {
            boolean inReach = hasTarget && target != null
                    && npc.getBoundingBox().inflate(MELEE_REACH).intersects(target.getBoundingBox());
            // 指令分两种：
            //  · 玩家手动移动（硬指令）→ 必须走完，战斗只在贴身时还手，绝不取消
            //  · 队伍跟随/阵型站位（软指令）→ 近战发现目标就放弃站位主动追
            // 以前不区分，一律取消：玩家刚点的移动会被吃掉；改回去又会让队伍跟随
            // 每 tick 重发，近战永远站在原地。
            if (!ranged && hasTarget && !inReach && npc.isSoftRtsOrder()) {
                npc.cancelRtsOrder();
                wasMoving = false;
            } else {
                wasMoving = true;
                tickCombatWhileMoving(level, ranged);
                return;
            }
        }
        if (wasMoving) {
            wasMoving = false;
            if (assignedTarget == null) {
                clearTarget();
            }
            scanCooldown = 0;
        }
        if (!hasTarget || target == null) {
            // hasTarget 是上面算的旧值：wasMoving 分支可能刚把 target 清掉，
            // 这里必须再查一次 target，否则下面 tickRangedApproach 会拿 null 调 distanceToSqr 直接崩服。
            return;
        }
        if (ranged) {
            tickRangedApproach(level);
        } else {
            tickChaseAndMelee();
        }
        if (target != null) {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private void tickCombatWhileMoving(ServerLevel level, boolean ranged) {
        refreshAssigned(level);
        if (target == null) {
            return;
        }
        // 行军途中不重新索敌：这里用 4.5 格校验，超出就把目标放掉继续走路。
        // （以前用 ensureTarget 再扫一遍，会把 24 格扫到的目标当场判无效清掉，
        //  于是每 tick 都在"扫到→清掉→再扫"之间空转。）
        if (!isTargetValid(level, ranged ? RANGED_RANGE : MELEE_MOVE_SCAN)) {
            if (assignedTarget == null) {
                clearTarget();
            }
            return;
        }
        if (ranged) {
            tickRanged(level);
        } else if (npc.getBoundingBox().inflate(MELEE_REACH).intersects(target.getBoundingBox())) {
            tickMelee();
        }
        if (target != null) {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private boolean ensureTarget(ServerLevel level, boolean ranged, double scanRange) {
        if (assignedTarget != null) {
            target = assignedTarget;
            return true;
        }
        // 巡逻共享目标：全队统一，优先于反击与自动索敌
        if (patrolTarget != null) {
            target = patrolTarget;
            return true;
        }
        // 被打反击：优先于自动索敌（低于 Ctrl 指定）。被突脸的远程必须立刻掉头打动手的人。
        if (retaliateTarget != null) {
            target = retaliateTarget;
            return true;
        }
        if (target != null && !isTargetValid(level, scanRange)) {
            clearTarget();
        }
        if (target != null) {
            return true;
        }
        if (--scanCooldown > 0) {
            return false;
        }
        scanCooldown = SCAN_INTERVAL + (npc.getId() & 7);
        acquire(level, ranged, scanRange);
        return target != null;
    }

    /**
     * 受击回调：被谁打就立刻把战斗目标换成谁。
     * Ctrl 指定的目标（assignedTarget）是玩家明确命令，不被自动反击覆盖。
     */
    void onHurtBy(Entity attacker) {
        if (attacker == null) {
            return;
        }
        // 巡逻态：被谁打就立刻把那个人变成全队的共享目标（谁都能替队友出头）
        if (mode == CombatMode.PATROL) {
            RtsPatrolService.onMemberHurt(npc, attacker);
            return;
        }
        if (assignedTarget != null || !(attacker instanceof LivingEntity living)) {
            return;
        }
        if (!npc.canAttack(living) || living == npc) {
            return;
        }
        if (target == living && retaliateTarget == living) {
            return;
        }
        retaliateTarget = living;
        target = living;
        attackTargetId = living.getUUID();
        losFailTicks = 0;
        chaseTicks = 0;
        scanCooldown = 0;
    }

    private void tickRangedApproach(ServerLevel level) {
        if (target == null) {
            return;
        }
        double distance = Math.sqrt(npc.distanceToSqr(target));
        if (distance > RANGED_APPROACH_RANGE) {
            if (beyondTeamLeash(target.position()) || beyondTeamLeash(npc.position())) {
                npc.getNavigation().stop();
                if (farBeyondTeamLeash(target.position()) || farBeyondTeamLeash(npc.position())) {
                    returnToAnchorIfFar();
                }
                return;
            }
            if (++chaseTicks > CHASE_GIVE_UP_TICKS) {
                chaseTicks = 0;
                clearTarget();
                return;
            }
            npc.tickPathTo(BlockPos.containing(target.getBoundingBox().getCenter()), false);
            return;
        }
        chaseTicks = 0;
        if (distance < RANGED_SAFE_RANGE && npc.tickCount % 20 == 0) {
            Vec3 away = npc.position().subtract(target.position());
            if (away.lengthSqr() > 1.0E-4D) {
                Vec3 spot = npc.position().add(away.normalize().scale(RANGED_RETREAT_STEP));
                if (!beyondTeamLeash(spot)) {
                    npc.tickPathTo(BlockPos.containing(spot), false);
                }
            }
        }
        tickRanged(level);
    }

    private boolean beyondTeamLeash(Vec3 point) {
        double sqr = anchorDistanceSqr(point);
        double leash = RtsTeamManager.TEAM_LEASH;
        return sqr >= 0.0D && sqr > leash * leash;
    }

    private boolean farBeyondTeamLeash(Vec3 point) {
        double sqr = anchorDistanceSqr(point);
        double leash = RtsTeamManager.TEAM_LEASH + 2.0D;
        return sqr >= 0.0D && sqr > leash * leash;
    }

    private double anchorDistanceSqr(Vec3 point) {
        BlockPos anchor = RtsTeamManager.anchorOf(npc.getUUID());
        if (anchor == null) {
            return -1.0D;
        }
        double dx = point.x - (anchor.getX() + 0.5D);
        double dy = point.y - anchor.getY();
        double dz = point.z - (anchor.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private void returnToAnchorIfFar() {
        BlockPos anchor = RtsTeamManager.anchorOf(npc.getUUID());
        if (anchor != null) {
            npc.setRtsTarget(anchor, false);
        }
    }

    private void tickChaseAndMelee() {
        if (target == null) {
            return;
        }
        if (Math.abs(npc.getY() - target.getY()) > MAX_VERTICAL_CHASE
                || ++chaseTicks > CHASE_GIVE_UP_TICKS) {
            chaseTicks = 0;
            clearTarget();
            return;
        }
        if (npc.getBoundingBox().inflate(MELEE_REACH).intersects(target.getBoundingBox())) {
            chaseTicks = 0;
            npc.getNavigation().stop();
            tickMelee();
            return;
        }
        if (beyondTeamLeash(target.position()) || beyondTeamLeash(npc.position())) {
            npc.getNavigation().stop();
            if (farBeyondTeamLeash(target.position()) || farBeyondTeamLeash(npc.position())) {
                returnToAnchorIfFar();
            }
            return;
        }
        npc.tickPathTo(BlockPos.containing(target.getBoundingBox().getCenter()), false);
    }

    /**
     * 坚守：只在攻击距离内索敌与攻击。
     * <p>
     * 所有目标（玩家 Ctrl 指定、被打反击、自动索敌）都必须留在攻击距离内，离开就释放。
     * 否则一个「挨了一下又跑开」的敌人会被永久锁死，把后面走进攻击距离的新敌人全挡住——
     * 之前 {@code ensureTarget} 对 assignedTarget / retaliateTarget 不做距离校验，就是这个症状的来源。
     */
    private void tickHold(ServerLevel level) {
        if (!npc.isArmed()) {
            clearAggro();
            return;
        }
        boolean ranged = CombatWeapons.isRanged(npc.weaponStack());
        npc.syncShieldPose(ranged);
        // 近战：索敌 4 格（略大于 3 格攻击距离，够得着就锁），释放 3 + 1.5 格迟滞
        // 远程：索敌/释放都是射程 100 格
        double scanRange = ranged ? RANGED_RANGE : MELEE_REACH + 1.0D;
        double releaseRange = ranged ? RANGED_RANGE : MELEE_REACH;

        refreshAssigned(level);
        // 玩家指定的目标同样受攻击距离约束：走出去了就作废，交还给就近索敌
        if (assignedTarget != null && !isEntityValid(level, assignedTarget, releaseRange)) {
            assignedTarget = null;
        }
        // 当前目标（含反击目标）离开攻击距离 → 清掉，下一拍重新就近索敌
        if (target != null && !isEntityValid(level, target, releaseRange)) {
            clearTarget();
            scanCooldown = 0;
        }
        if (target == null) {
            if (--scanCooldown > 0) {
                return;
            }
            scanCooldown = SCAN_INTERVAL + (npc.getId() & 7);
            acquire(level, ranged, scanRange);
            if (target == null) {
                return;
            }
        }
        if (ranged) {
            tickRanged(level);
        } else {
            tickMelee();
        }
        if (target != null) {
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private void refreshAssigned(ServerLevel level) {
        if (assignedTarget == null) {
            return;
        }
        if (assignedTarget.isRemoved() || !assignedTarget.isAlive()
                || assignedTarget.level() != level) {
            assignedTarget = null;
        }
    }

    void assignTarget(Entity entity) {
        if (entity != null && (!(entity instanceof LivingEntity living) || !npc.canAttack(living))) {
            return;
        }
        // 玩家 Ctrl 指定的目标压过巡逻共享目标，顺手把巡逻目标摘掉，免得两份目标互相打架
        patrolTarget = null;
        assignedTarget = entity;
        target = entity;
        attackTargetId = entity == null ? null : entity.getUUID();
        losFailTicks = 0;
        chaseTicks = 0;
        scanCooldown = 0;
        if (mode == CombatMode.STANDBY && entity != null) {
            mode = CombatMode.ATTACK;
        }
    }

    void clearAggro() {
        assignedTarget = null;
        patrolTarget = null;
        clearTarget();
        scanCooldown = 0;
    }

    /** 巡逻服务广播下来的共享目标：全队打同一只，优先级低于玩家 Ctrl 指定。 */
    void assignPatrolTarget(Entity entity) {
        if (!npc.isArmed()) {
            // 没持械的队员不参战，也就不能给它挂目标——否则它不会跑 tickPatrol，目标永远清不掉
            return;
        }
        if (entity == null || !(entity instanceof LivingEntity living) || !npc.canAttack(living)) {
            return;
        }
        patrolTarget = entity;
        target = entity;
        attackTargetId = entity.getUUID();
        losFailTicks = 0;
        chaseTicks = 0;
        scanCooldown = 0;
    }

    void clearPatrolTarget() {
        Entity previous = patrolTarget;
        patrolTarget = null;
        if (previous != null && target == previous) {
            clearTarget();
        }
    }

    void beginPatrol() {
        patrolTarget = null;
        setMode(CombatMode.PATROL);
    }

    private boolean isTargetValid(ServerLevel level, double range) {
        return isEntityValid(level, target, range);
    }

    /** 目标是否还能打：存活、可攻击、且中心距离不超过 {@code range + TARGET_LEASH}。 */
    private boolean isEntityValid(ServerLevel level, Entity entity, double range) {
        if (entity == null) {
            return false;
        }
        if (entity.isRemoved() || !entity.isAlive() || entity.level() != level) {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !npc.canAttack(living)) {
            return false;
        }
        double distance = Math.sqrt(npc.getBoundingBox().getCenter()
                .distanceToSqr(entity.getBoundingBox().getCenter()));
        return distance <= range + TARGET_LEASH;
    }

    private void acquire(ServerLevel level, boolean ranged, double range) {
        Entity candidate = ThreatIndex.nearest(level, npc.position(), range);
        if (!(candidate instanceof LivingEntity living) || candidate == npc
                || !npc.canAttack(living)) {
            return;
        }
        if (ranged && !npc.getSensing().hasLineOfSight(candidate)) {
            return;
        }
        target = candidate;
        attackTargetId = candidate.getUUID();
        losFailTicks = 0;
    }

    private void tickMelee() {
        if (target == null) {
            return;
        }
        AABB reach = npc.getBoundingBox().inflate(MELEE_REACH);
        if (!reach.intersects(target.getBoundingBox())) {
            return;
        }
        if (attackCooldown > 0) {
            return;
        }
        attackCooldown = MELEE_COOLDOWN;
        npc.playSwing();
        npc.swing(InteractionHand.MAIN_HAND);
        npc.doHurtTarget(target);
    }

    private void tickRanged(ServerLevel level) {
        if (target == null) {
            return;
        }
        double distance = Math.sqrt(npc.distanceToSqr(target));
        if (distance < RANGED_MIN_RANGE || distance > RANGED_RANGE) {
            return;
        }
        if (!npc.getSensing().hasLineOfSight(target)) {
            if (assignedTarget == null && patrolTarget == null && ++losFailTicks > LOS_FAIL_LIMIT) {
                clearTarget();
            }
            return;
        }
        losFailTicks = 0;
        // 端弓姿势：像骷髅一样举着弓/弩，客户端会按 using 状态摆 BOW_AND_ARROW 姿势。
        // 我们射箭不依赖物品释放，所以这里一直保持 using，视觉上就是"举着瞄"。
        if (!npc.isUsingItem()) {
            npc.startUsingItem(InteractionHand.MAIN_HAND);
        }
        if (attackCooldown > 0) {
            return;
        }
        boolean crossbow = npc.weaponStack().is(Items.CROSSBOW);
        attackCooldown = crossbow ? CROSSBOW_COOLDOWN : BOW_COOLDOWN;
        shoot(level, target, crossbow);
    }

    private void shoot(ServerLevel level, Entity victim, boolean crossbow) {
        double speed = crossbow ? ARROW_SPEED_CROSSBOW : ARROW_SPEED_BOW;
        Vec3 origin = shootOrigin();
        Vec3 aim = leadAim(origin, victim, speed);
        Vec3 jittered = jitterAim(origin, aim, victim);
        Vec3 velocity = solveLaunchVelocity(origin, jittered, speed);

        Arrow arrow = new Arrow(level, npc, ItemStack.EMPTY, npc.weaponStack());
        arrow.setPos(origin.x, origin.y, origin.z);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        // 伤害不自己设：原版 AbstractArrow 默认 baseDamage = 2.0，
        // 命中时伤害 = ceil(速度 × 2.0)，再由 EnchantmentHelper.modifyDamage(弓的力量附魔) 加成。
        arrow.shoot(velocity.x, velocity.y, velocity.z, (float) speed, 0.0F);
        level.addFreshEntity(arrow);
        npc.playSwing();
    }

    private Vec3 shootOrigin() {
        Vec3 look = npc.getLookAngle();
        double height = npc.getY() + npc.getBbHeight() * ARM_HEIGHT_FACTOR;
        return new Vec3(npc.getX() + look.x * 0.35D, height, npc.getZ() + look.z * 0.35D);
    }

    /** 提前量：用上一轮解出的飞行时间把瞄准点推到目标未来位置，两次迭代足够收敛。 */
    private Vec3 leadAim(Vec3 origin, Entity victim, double speed) {
        Vec3 center = victim.getBoundingBox().getCenter();
        Vec3 motion = victim.getDeltaMovement();
        Vec3 aim = center;
        double[] solved = solveVelocityTo(origin, aim, speed);
        for (int iteration = 0; iteration < 2; iteration++) {
            double flight = solved[2];
            if (!Double.isFinite(flight) || flight <= 0.0D) {
                break;
            }
            aim = center.add(motion.scale(flight));
            solved = solveVelocityTo(origin, aim, speed);
        }
        return aim;
    }

    /**
     * 在**目标所在平面**上做散布：把瞄准点抖到目标平面内的随机点，再对这个点解弹道。
     * <p>
     * 旧实现是把偏移直接加到速度向量上——0.7 的偏移除以 2.4 的初速等于 **16° 的角度抖动**，
     * 10 格外就偏离近 3 格，命中率自然惨不忍睹。改成目标平面偏移后，散布半径就是目标处的空间半径。
     */
    private Vec3 jitterAim(Vec3 origin, Vec3 aim, Entity victim) {
        Vec3 direction = aim.subtract(origin);
        if (direction.lengthSqr() < 1.0E-6D) {
            return aim;
        }
        direction = direction.normalize();
        Vec3 right = direction.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() < 1.0E-6D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize();
        Vec3 up = right.cross(direction).normalize();

        double radius = spreadRadius(direction, victim);
        double sampleRadius = radius * Math.sqrt(npc.getRandom().nextDouble());
        double angle = npc.getRandom().nextDouble() * Math.PI * 2.0D;
        return aim.add(right.scale(sampleRadius * Math.cos(angle)))
                .add(up.scale(sampleRadius * Math.sin(angle)));
    }

    /**
     * 解算命中 aim 的初速向量。角度口径（项目约定）：
     * NPC 站立身体为竖直线，垂直向上 0°、转到水平 90°，只允许**上半 90°**。
     * 这里的竖直分量恒为 {@code vy >= 0}，不会解出向下压枪的角度；
     * 目标在下方时最多压到水平（90°），靠重力自然下坠命中。
     */
    private Vec3 solveLaunchVelocity(Vec3 origin, Vec3 aim, double speed) {
        double dx = aim.x - origin.x;
        double dz = aim.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double[] solved = solveVelocityTo(origin, aim, speed);
        double vy = solved[0];
        double vh = solved[1];
        if (horizontal < 1.0E-3D) {
            // 目标几乎在正上方 / 正下方
            if (aim.y - origin.y >= 0.0D) {
                return new Vec3(0.0D, speed, 0.0D);                     // 0°：垂直向上
            }
            Vec3 look = npc.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                lx = 1.0D;
                lz = 0.0D;
                len = 1.0D;
            }
            return new Vec3(lx / len * speed, 0.0D, lz / len * speed);  // 90°：水平
        }
        return new Vec3(dx / horizontal * vh, vy, dz / horizontal * vh);
    }

    /**
     * 固定初速大小，解出命中 aim 的竖直分量 vy 与水平分量 vh，返回 {@code {vy, vh, 飞行tick}}。
     * <p>
     * 原版 {@code AbstractArrow.tick} 的实际顺序是「先 {@code p += v}，再 {@code v = 0.99v − 0.05ŷ}」，
     * 因此 T tick 后的位移是
     * <pre>
     *   p(T) = v0 · G(T) − 5 · (T − G(T)) · ŷ ，  G(T) = (1 − 0.99^T) / 0.01
     * </pre>
     * 水平方向 {@code H = vh · G}，所以 {@code G = H / vh}，代回竖直方程即可解 vy。
     * 旧实现把重力项写成 {@code 0.05·G}（少算 10 倍），远距离全部打低，是最主要的脱靶原因。
     * <p>
     * vy 搜索区间限制在 {@code [0, speed)}：0 = 水平（90°）、speed = 垂直向上（0°），不存在负角度。
     * 落点方程在远距离并非单调（越过最大射程后会重新下降），所以先粗扫全区间取最接近的，
     * 再在相邻两格内二分细化，避免普通二分落到错误分支。
     */
    private double[] solveVelocityTo(Vec3 origin, Vec3 aim, double speed) {
        double dx = aim.x - origin.x;
        double dy = aim.y - origin.y;
        double dz = aim.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.15D) {
            double vy = dy >= 0.0D ? speed : 0.0D;
            double vh = Math.sqrt(Math.max(1.0E-6D, speed * speed - vy * vy));
            return new double[]{vy, vh, horizontal / Math.max(vh, 1.0E-4D)};
        }
        final int samples = 96;
        double maxVy = speed * 0.995D;
        double step = maxVy / samples;
        double[] predicted = new double[samples + 1];
        int best = 0;
        double bestError = Double.MAX_VALUE;
        for (int index = 0; index <= samples; index++) {
            double vy = step * index;
            predicted[index] = predictedHeight(horizontal, vy, speed);
            double error = Math.abs(predicted[index] - dy);
            if (error < bestError) {
                bestError = error;
                best = index;
            }
        }
        int lowIndex = Math.max(0, best - 1);
        int highIndex = Math.min(samples, best + 1);
        double lowVy = step * lowIndex;
        double highVy = step * highIndex;
        boolean rising = predicted[highIndex] >= predicted[lowIndex];
        for (int iteration = 0; iteration < 32; iteration++) {
            double mid = (lowVy + highVy) * 0.5D;
            double value = predictedHeight(horizontal, mid, speed);
            boolean below = rising ? value < dy : value > dy;
            if (below) {
                lowVy = mid;
            } else {
                highVy = mid;
            }
        }
        double vy = Math.max(0.0D, (lowVy + highVy) * 0.5D);
        double vh = Math.sqrt(Math.max(1.0E-9D, speed * speed - vy * vy));
        double g = horizontal / vh;
        double flight = g >= 99.0D
                ? 400.0D
                : Math.log(1.0D - (1.0D - ARROW_DRAG) * g) / Math.log(ARROW_DRAG);
        return new double[]{vy, vh, flight};
    }

    /** 给定水平距离与竖直初速，算 T tick 后能到达的相对高度。 */
    private double predictedHeight(double horizontal, double vy, double speed) {
        double vh = Math.sqrt(Math.max(1.0E-9D, speed * speed - vy * vy));
        double g = horizontal / vh;
        if (g >= 99.0D) {
            return -1.0E4D;
        }
        double flight = Math.log(1.0D - (1.0D - ARROW_DRAG) * g) / Math.log(ARROW_DRAG);
        return vy * g - GRAVITY_CARRY * (flight - g);
    }

    /**
     * 按目标在**弹道垂直面**上的投影面积反解散布圆盘半径，命中率 ≈ {@link #HIT_RATE_TARGET}。
     * 越高抛，竖直投影越短（{@code h·cos + w·sin}），圆盘也要随之收小。
     */
    private double spreadRadius(Vec3 direction, Entity victim) {
        AABB box = victim.getBoundingBox();
        double width = Math.max(0.5D, box.maxX - box.minX);
        double height = Math.max(0.5D, box.maxY - box.minY);
        double depth = Math.max(0.5D, box.maxZ - box.minZ);
        double sinPitch = Math.abs(direction.normalize().y);
        double cosPitch = Math.sqrt(Math.max(0.0D, 1.0D - sinPitch * sinPitch));
        double area = width * (height * cosPitch + depth * sinPitch);
        double radius = Math.sqrt(area / (Math.PI * HIT_RATE_TARGET));
        return Math.max(MIN_SPREAD_RADIUS, Math.min(MAX_SPREAD_RADIUS, radius));
    }

    private void clearTarget() {
        target = null;
        attackTargetId = null;
        retaliateTarget = null;
        losFailTicks = 0;
        chaseTicks = 0;
        // 脱离战斗时把端弓姿势收起来，否则会一直举着弓站着
        if (npc.isUsingItem()) {
            npc.stopUsingItem();
        }
    }

    NpcDetail detail() {
        return switch (mode) {
            case STANDBY -> NpcDetail.ARMED_IDLE;
            case PATROL -> NpcDetail.PATROLLING;
            case HOLD -> NpcDetail.HOLDING_GROUND;
            case ATTACK -> NpcDetail.ATTACKING;
        };
    }

    CombatMode mode() {
        return mode;
    }

    void setMode(CombatMode value) {
        CombatMode next = value == null ? CombatMode.STANDBY : value;
        if (next != mode) {
            if (mode == CombatMode.PATROL && next != CombatMode.PATROL) {
                patrolTarget = null;
            }
            clearTarget();
            scanCooldown = 0;
            wasMoving = false;
            if (next == CombatMode.STANDBY) {
                // 回待命就放下盾，别一直摆着举盾姿势
                npc.lowerShield();
            }
        }
        mode = next;
    }

    void beginAttack() {
        setMode(CombatMode.ATTACK);
    }

    boolean hasTarget() {
        return target != null;
    }

    /** 是否还挂着巡逻共享目标（巡逻服务用它判断这一仗打完没有）。 */
    boolean hasPatrolTarget() {
        return patrolTarget != null;
    }

    UUID attackTarget() {
        return attackTargetId;
    }

    void setAttackTarget(UUID targetId) {
        attackTargetId = targetId;
        if (targetId != null) {
            mode = CombatMode.ATTACK;
        }
    }

    void setPatrolPoint(BlockPos pos) {
        patrolPoint = pos;
        if (pos != null) {
            setMode(CombatMode.PATROL);
        }
    }

    void setHoldPoint(BlockPos pos) {
        holdPoint = pos;
        if (pos != null) {
            setMode(CombatMode.HOLD);
        }
    }

    BlockPos patrolPoint() {
        return patrolPoint;
    }

    BlockPos holdPoint() {
        return holdPoint;
    }

    void clearOrders() {
        setMode(CombatMode.STANDBY);
        patrolPoint = null;
        holdPoint = null;
    }
}
