package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.npc.*;
import com.xy2407.kingdom.common.path.NpcPathRetry;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 市民实体，按状态机闲逛决策执行游走/社交/坐下。
 */
public class NpcEntity extends PathfinderMob {
    private static final String KEY_CITIZEN_ID = "CitizenId";
    private static final EntityDataAccessor<Boolean> DATA_SITTING =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_HUNGER =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> DATA_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_STATUS =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<ItemStack> DATA_HELD_ITEM =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> DATA_SWING =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /* 天赋三项：客户端要按它画身份卡片的三角面板，跟着实体同步（0 = 还没同步到）。 */
    private static final EntityDataAccessor<Integer> DATA_STRENGTH =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_INTELLIGENCE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AGILITY =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final double WANDER_RADIUS = 10.0D;
    private static final double SIT_WALK_RADIUS = 4.0D;

    private final NpcInventory npcInventory = createInventory();
    private boolean inventoryLoading;
    private static final double SOCIALIZE_RADIUS = 12.0D;
    private static final double SOCIALIZE_GAP = 1.0D;
    private static final double WALK_SPEED = 0.5D;
    private static final double ARRIVE_SQR = 1.0D;
    private static final double WORK_ARRIVE_SQR = 4.0D;
    private static final double HOME_ARRIVE_SQR = 4.0D;
    private static final int STATIONARY_TRAVEL_INTERVAL = 20;
    private static final int RTS_REPATH_INTERVAL = 40;
    private static final double RTS_DENSE_ARRIVE_SQR = 0.5625D;
    private static final double RTS_CROWD_ARRIVE_SQR = 2.25D;
    private static final double RTS_ARRIVE_HEIGHT = 3.0D;
    private static final int WORK_STUCK_TICKS = 40;
    private static final int WORK_STUCK_LIMIT = 3;
    private static final int WORK_SEARCH_FAIL_LIMIT = 8;
    private static final long WORK_PATH_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int WORK_CLIMB_INTERVAL_TICKS = 10;
    private static final double WORK_FLOOR_MIN_Y = 0.45D;
    private static final double WORK_HEADROOM_MAX_Y = 0.1D;

    private static final int FAR_TRAVEL_STRIDE = 2;
    private static final double FAR_TRAVEL_RADIUS = 64.0D;
    private static final int FAR_TRAVEL_RECHECK_TICKS = 40;

    private int farTravelStrideCache = 1;
    private int farTravelCheckTimer;

    private UUID citizenId;
    private boolean sitWalking;
    private boolean socialized;
    private NpcDetail activeDetail;
    private NpcBrain.WanderStyle activeStyle;
    private NpcEntity socialPartner;
    private ResourceKey<Level> cachedDimension;
    private String cachedDimensionId;
    private Vec3 lastTravelPos;
    private int stillTicks;
    private BlockPos forageTarget;
    private BlockPos farmTarget;
    private BlockPos ranchTarget;
    private BlockPos navTarget;
    private final NpcPathRetry pathRetry = new NpcPathRetry();
    private BlockPos rtsTarget;
    private boolean rtsClearOnArrive;
    private boolean rtsHighPath;
    private double rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
    private int rtsRepathCooldown;
    private final ArrayDeque<BlockPos> rtsWaypoints = new ArrayDeque<>();
    private Future<NpcPathfinder.PathResult> workPathFuture;
    /** 可续跑寻路会话：同一目标的升档重试会接着上次的搜索图往下跑，不重算。 */
    private NpcPathfinder.Session workSession;
    private int workRepathCooldown;
    private long workPathDeadlineNanos;
    private int workClimbCooldown;
    private BlockPos workStandSource;
    private BlockPos workStand;
    private BlockPos workAnchor;
    private int workStuckTicks;
    private int workFailures;
    /** 寻路余量档位：搜不到就逐级放大（4 → 12 → 24），成功或放弃后归零。 */
    private int pathPaddingStage;

    public void setRtsTarget(BlockPos target, boolean clearOnArrive) {
        rtsWaypoints.clear();
        applyRtsTarget(target, clearOnArrive, false);
    }

    public void setHighRtsTarget(BlockPos target, boolean clearOnArrive) {
        rtsWaypoints.clear();
        applyRtsTarget(target, clearOnArrive, true);
    }

    public void setRtsWaypoints(List<BlockPos> waypoints) {
        rtsWaypoints.clear();
        if (waypoints == null || waypoints.isEmpty()) {
            return;
        }
        rtsWaypoints.addAll(waypoints);
        rtsTarget = rtsWaypoints.peekLast();
        rtsClearOnArrive = false;
        rtsArriveSqr = RTS_CROWD_ARRIVE_SQR;
        rtsHighPath = true;
        rtsRepathCooldown = 0;
        stopConstructionPath();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        applyPath(new ArrayList<>(rtsWaypoints));
        setNavTarget(rtsTarget);
    }

    public List<BlockPos> getRtsWaypoints() {
        return new ArrayList<>(rtsWaypoints);
    }

    public void clearRtsWaypoints() {
        rtsWaypoints.clear();
    }

    /**
     * 交还作息控制权：清掉 RTS 目标与标志，让 brain 重新按排班接管这个人。
     * 走到终点后必须叫一次，否则 {@code rtsControlled} 会一直挂着，
     * brain 每 tick 都只会把 detail 设成 COMMANDED_MOVE。
     */
    private void releaseRtsControl() {
        rtsTarget = null;
        rtsWaypoints.clear();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(false);
        }
    }

    /**
     * 建筑整体迁移后调用：丢掉所有指向旧坐标的临时目标。
     * 不清的话，市民会继续往已经搬走的位置走（农活点、畜栏点、寻食点、RTS 航点都是绝对值）。
     */
    public void clearMoveTargets() {
        forageTarget = null;
        farmTarget = null;
        ranchTarget = null;
        rtsTarget = null;
        rtsWaypoints.clear();
        clearNavTarget();
    }

    private void applyRtsTarget(BlockPos target, boolean clearOnArrive, boolean highPath) {
        rtsTarget = target;
        rtsClearOnArrive = clearOnArrive;
        rtsHighPath = highPath;
        rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
        rtsRepathCooldown = 0;
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        if (highPath) {
            // 接管这个人的走路：先清掉作息寻路留下的挂起搜索与缓存站位。
            // 否则 tickPathTo 会先消费掉上一份（去工位/回家）的搜索结果，把人带偏。
            stopConstructionPath();
        } else {
            getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
        }
        setNavTarget(target);
    }

    private void tickRtsCommand() {
        BlockPos target = rtsTarget;
        if (target == null) {
            stopSitting();
            getNavigation().stop();
            return;
        }
        if (isRtsArrived(target)) {
            rtsHighPath = false;
            rtsWaypoints.clear();
            if (rtsClearOnArrive) {
                discard();
                return;
            }
            stopSitting();
            getNavigation().stop();
            // 到站即交还作息控制权。通勤调度与自动寻路都是借道这套 RTS 寻路设施的
            // （CommuteScheduler / NpcPathRetry 都调 applyPath），而 applyPath 会把
            // brain 打成 rtsControlled；不在这里交还，brain 就永远停在 COMMANDED_MOVE，
            // applySchedule 再也轮不到，回家睡觉自然全程不会发生。
            releaseRtsControl();
            return;
        }
        stopSitting();
        if (rtsHighPath) {
            tickHighPath(target);
            return;
        }
        if (getNavigation().isInProgress()) {
            rtsRepathCooldown = 0;
        } else if (rtsRepathCooldown-- <= 0) {
            rtsRepathCooldown = RTS_REPATH_INTERVAL;
            getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
            setNavTarget(target);
        }
        pathRetry.tick(this);
    }

    /**
     * RTS 高层移动（{@code /kingdom test move highmove}）。
     * <p>
     * 直接复用 {@link #tickPathTo} 的完整执行链：贴地 → 自定义多层 A* → 缩余量升档重试
     * → {@link #assistClimb} 主动起跳爬台阶 → 卡死兜底（含最后的传送保底）。
     * <p>
     * 原来这里是自己 submitSearch + applyPath，把 A* 航点丢给原版导航后就不管了，
     * 缺的正是 assistClimb —— 原版导航不会主动跳上台阶，人走到楼梯前只能对着
     * 下一个节点原地转向；导航卡断后这里又立刻从当前位置重算，于是每轮换一条路，
     * 表现就是"走到楼梯一半又退回来、最后卡在门口转圈"。
     */
    private void tickHighPath(BlockPos target) {
        if (tickPathTo(target)) {
            // 到位了：停住。后续交给 tickRtsCommand 的 isRtsArrived 判定并交还控制权，
            // 这里不动 rtsHighPath，免得中途退回原版单层导航。
            getNavigation().stop();
        }
    }

    private NpcPathfinder.PathResult takeResult(Future<NpcPathfinder.PathResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private boolean isRtsArrived(BlockPos target) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        double limit = rtsClearOnArrive ? RTS_CROWD_ARRIVE_SQR : rtsArriveSqr;
        return dx * dx + dz * dz < limit && Math.abs(getY() - target.getY()) < RTS_ARRIVE_HEIGHT;
    }

    /** 施工用：把目标解析成站立点后，走公共的多层寻路。 */
    public boolean tickConstructionPath(BlockPos target) {
        return tickPathTo(target);
    }

    /**
     * 用自定义 A*（多层、可爬升）走向目标点，返回是否已到达。
     * <p>
     * GOING_WORK / GOING_HOME / SLEEPING 都走这里 —— 原版 {@code getNavigation()} 只有单层，
     * 碰到墙、台阶、多层建筑会原地卡死，所以这几条路统一换成自定义寻路。
     * <p>
     * 搜出来的航点交给 {@link #applyPath} 执行，它会打上 {@code rtsControlled}，
     * 由 {@link #tickRtsCommand} 走到终点后调用 {@code releaseRtsControl()} 交还作息控制权；
     * 于是 brain 下一轮又能按排班继续修正，人没到家就再发起一次，接力走到为止。
     */
    private boolean tickPathTo(BlockPos target) {
        stopSitting();
        BlockPos stand = resolveWorkStand(target);
        double dx = getX() - (stand.getX() + 0.5D);
        double dz = getZ() - (stand.getZ() + 0.5D);
        if (dx * dx + dz * dz < 1.0D && Math.abs(getY() - stand.getY()) < 1.5D) {
            stopConstructionPath();
            return true;
        }
        if (getNavigation().isInProgress()) {
            if (trackWorkStuck()) {
                escalateWork(stand, WORK_STUCK_LIMIT);
            } else {
                assistClimb();
            }
            return false;
        }
        workAnchor = null;
        workStuckTicks = 0;
        if (workPathFuture != null) {
            if (!workPathFuture.isDone() && System.nanoTime() < workPathDeadlineNanos) {
                return false;
            }
            Future<NpcPathfinder.PathResult> future = workPathFuture;
            workPathFuture = null;
            NpcPathfinder.PathResult result = future.isDone() ? takeResult(future) : null;
            future.cancel(false);
            List<BlockPos> waypoints = result == null ? null : result.waypoints();
            if (waypoints != null && waypoints.size() > 1) {
                workRepathCooldown = 20;
                workFailures = 0;
                pathPaddingStage = 0;
                applyPath(waypoints);
            } else if (result != null && result.missingChunks() > 0) {
                // 搜不到，但覆盖范围内还有区块没建好 —— 这是"数据还没到"，
                // 不是"路不存在"。保持当前档位等下一轮重试即可；
                // 此时放大余量纯属浪费（还要多入队一大批区块）。
                workRepathCooldown = 10;
            } else {
                // 真的搜不到：放大余量再试（4 → 12 → 24）。绕远路的路线（例如绕到
                // 建筑另一侧上楼梯）在小余量下会直接撞出快照边界，只有把搜索范围
                // 放大才可能搜到；一直卡在最小档用同样参数重试，等于永远搜不到。
                pathPaddingStage++;
                workRepathCooldown = 10;
                escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
            }
            return false;
        }
        if (workRepathCooldown > 0) {
            workRepathCooldown--;
            return false;
        }
        if (!(level() instanceof ServerLevel level)) {
            return false;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return false;
        }
        BlockPos from = blockPosition();
        // 会话绑定同一组起终点：升档重试时接着上次那张搜索图往下跑（保留已算出的 g 值
        // 与前沿），而不是从起点重算最多 8192 个节点。目标一变就重建会话。
        NpcPathfinder.Session session = workSession;
        if (session == null || !session.matches(from, stand)) {
            session = new NpcPathfinder.Session(from, stand);
            workSession = session;
        }
        Future<NpcPathfinder.PathResult> future = repository.submitSessionSearch(level, session, from, stand,
                NpcPathfinder.paddingOf(pathPaddingStage));
        if (future == null) {
            workRepathCooldown = 10;
            escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
            return false;
        }
        workPathFuture = future;
        workPathDeadlineNanos = System.nanoTime() + WORK_PATH_TIMEOUT_NANOS;
        return false;
    }

    public void stopConstructionPath() {
        if (workPathFuture != null) {
            workPathFuture.cancel(false);
            workPathFuture = null;
        }
        workRepathCooldown = 0;
        workPathDeadlineNanos = 0L;
        workClimbCooldown = 0;
        workStandSource = null;
        workStand = null;
        workAnchor = null;
        workStuckTicks = 0;
        workFailures = 0;
        pathPaddingStage = 0;
        workSession = null;
        if (getNavigation().isInProgress()) {
            getNavigation().stop();
        }
    }

    private boolean trackWorkStuck() {
        BlockPos now = blockPosition();
        if (workAnchor == null || !workAnchor.equals(now)) {
            workAnchor = now;
            workStuckTicks = 0;
            return false;
        }
        return ++workStuckTicks >= WORK_STUCK_TICKS;
    }

    private void escalateWork(BlockPos stand, int limit) {
        workAnchor = null;
        workStuckTicks = 0;
        workRepathCooldown = 0;
        workPathDeadlineNanos = 0L;
        workClimbCooldown = 0;
        getNavigation().stop();
        if (level() instanceof ServerLevel serverLevel) {
            SnapshotRepository repository = SnapshotRepository.get(serverLevel.getServer());
            if (repository != null) {
                repository.refreshNow(serverLevel, blockPosition());
                repository.refreshNow(serverLevel, stand);
            }
        }
        if (++workFailures < limit) {
            return;
        }
        workFailures = 0;
        setDeltaMovement(Vec3.ZERO);
        teleportTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
    }

    private BlockPos resolveWorkStand(BlockPos target) {
        if (target.equals(workStandSource)) {
            return workStand == null ? target : workStand;
        }
        workStandSource = target;
        workStand = standable(target) ? target : findStandableNear(target);
        return workStand == null ? target : workStand;
    }

    private BlockPos findStandableNear(BlockPos target) {
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 0; dy >= -2; dy--) {
                        BlockPos candidate = target.offset(dx, dy, dz);
                        if (standable(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean standable(BlockPos pos) {
        if (!level().isLoaded(pos) || !standsOn(pos.below())) {
            return false;
        }
        return passable(pos) && passable(pos.above());
    }

    private boolean standsOn(BlockPos pos) {
        VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= WORK_FLOOR_MIN_Y;
    }

    private boolean passable(BlockPos pos) {
        VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= WORK_HEADROOM_MAX_Y;
    }

    private void assistClimb() {
        if (workClimbCooldown > 0) {
            workClimbCooldown--;
            return;
        }
        Path path = getNavigation().getPath();
        Node node = path == null || path.isDone() ? null : path.getNextNode();
        // 用"脚底所在的层"而不是 blockPosition().y 去比：站在灵魂沙(0.875)、
        // 土径(0.9375)这类不满一格的方块上时，脚底 y 落在方块格内部，
        // blockPosition() 会比真实站立格低 1，平地行走也会被判成"要上台阶"，
        // 人就莫名其妙地跳。+0.5 归一正好还原出真正的站立格。
        if (node == null || node.y <= Math.floor(getY() + 0.5D) || (!onGround() && !onClimbable())) {
            return;
        }
        if (getDeltaMovement().y > 0.02D) {
            return;
        }
        workClimbCooldown = WORK_CLIMB_INTERVAL_TICKS;
        getJumpControl().jump();
    }

    public BlockPos getNavTarget() {
        return navTarget;
    }

    public void setNavTarget(BlockPos target) {
        navTarget = target;
        pathRetry.reset();
    }

    public BlockPos getForageTarget() {
        return forageTarget;
    }

    public void setForageTarget(BlockPos target) {
        forageTarget = target;
        // 觅食目标常在餐厅室内或楼上，走自定义 A*（多层、可爬梯），原版单层寻路会卡
        tickPathTo(target);
        setNavTarget(target);
    }

    public void clearForageTarget() {
        if (forageTarget == null) {
            return;
        }
        forageTarget = null;
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    public void haltForage() {
        clearForageTarget();
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    public BlockPos getFarmTarget() {
        return farmTarget;
    }

    public void setFarmTarget(BlockPos target) {
        farmTarget = target;
        // 作物格/棚架多在梯田或高台上，走自定义 A*
        tickPathTo(target);
        setNavTarget(target);
    }

    public void haltFarm() {
        farmTarget = null;
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    public BlockPos getRanchTarget() {
        return ranchTarget;
    }

    public void setRanchTarget(BlockPos target) {
        ranchTarget = target;
        // 畜栏/饲料槽常被栅栏围着、或有台阶，走自定义 A*
        tickPathTo(target);
        setNavTarget(target);
    }

    public void haltRanch() {
        ranchTarget = null;
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    public void applyPath(List<BlockPos> waypoints) {
        List<Node> nodes = new ArrayList<>(waypoints.size());
        for (BlockPos pos : waypoints) {
            Node node = new Node(pos.getX(), pos.getY(), pos.getZ());
            node.type = PathType.WALKABLE;
            node.costMalus = 0.0F;
            nodes.add(node);
        }
        navTarget = waypoints.get(waypoints.size() - 1);
        getNavigation().stop();
        getNavigation().moveTo(new Path(nodes, navTarget, false), WALK_SPEED);
    }

    public void onPathAbandoned() {
        navTarget = null;
        clearForageTarget();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().onPathAbandoned();
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || food.nutrition() <= 0) {
            return backpackInteract(player, hand, stack);
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data == null || data.getHunger() >= NpcData.HUNGER_MAX) {
            return InteractionResult.PASS;
        }
        data.setHunger(Math.min(NpcData.HUNGER_MAX, data.getHunger() + food.nutrition()));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        return InteractionResult.CONSUME;
    }

    private InteractionResult backpackInteract(Player player, InteractionHand hand, ItemStack stack) {
        if (!stack.isEmpty() || player.isShiftKeyDown() || hand != InteractionHand.MAIN_HAND) {
            return super.mobInteract(player, hand);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NpcBackpackMenu.open(serverPlayer, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (level().isClientSide()) {
            lastTravelPos = null;
            stillTicks = 0;
            super.travel(travelVector);
            return;
        }
        int stride = farTravelStride();
        if (stride > 1) {
            lastTravelPos = null;
            stillTicks = 0;
            if (Math.floorMod(tickCount + getId(), stride) != 0) {
                return;
            }
            super.travel(travelVector.scale(stride));
            return;
        }
        if (!canThrottleTravel()) {
            lastTravelPos = null;
            stillTicks = 0;
            super.travel(travelVector);
            return;
        }
        Vec3 current = position();
        boolean stable = lastTravelPos != null && current.distanceToSqr(lastTravelPos) < 1.0E-7D;
        lastTravelPos = current;
        if (!stable) {
            stillTicks = 1;
            super.travel(travelVector);
            return;
        }
        if (++stillTicks % STATIONARY_TRAVEL_INTERVAL != 0) {
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        super.travel(travelVector);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null && data.getBrain().isConstructionControlled()) {
            return false;
        }
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    @Override
    protected void pushEntities() {
        if (tickCount % 10 != 0) {
            return;
        }
        super.pushEntities();
    }

    private int farTravelStride() {
        if (--farTravelCheckTimer > 0) {
            return farTravelStrideCache;
        }
        farTravelCheckTimer = FAR_TRAVEL_RECHECK_TICKS;
        farTravelStrideCache = level().getNearestPlayer(getX(), getY(), getZ(), FAR_TRAVEL_RADIUS, false) == null
                ? FAR_TRAVEL_STRIDE
                : 1;
        return farTravelStrideCache;
    }

    private boolean canThrottleTravel() {
        if (!onGround() || isInWater() || isInLava() || onClimbable()) {
            return false;
        }
        if (isPassenger() || isVehicle()) {
            return false;
        }
        Vec3 movement = getDeltaMovement();
        if (movement.horizontalDistanceSqr() > 1.0E-4D) {
            return false;
        }
        // 竖直方向同样不能忽略：跳跃初速度是 LivingEntity 在 travel 之前由
        // jumpFromGround 设好的，而"站在楼梯口正要起跳"这一帧 onGround 仍为 true、
        // 水平速度仍为 0，只查水平速度会把它判成静止，travel 随即 setDeltaMovement(ZERO)
        // 抹掉这 0.42 —— 结果就是人一直在楼梯前跳不起来、原地卡住。
        if (Math.abs(movement.y) > 1.0E-4D) {
            return false;
        }
        return movement.y > -0.12D && movement.y < 0.05D;
    }

    private String dimensionId() {
        ResourceKey<Level> key = level().dimension();
        if (key != cachedDimension) {
            cachedDimension = key;
            cachedDimensionId = key.location().toString();
        }
        return cachedDimensionId;
    }

    public NpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SITTING, false);
        builder.define(DATA_HUNGER, 0.0F);
        builder.define(DATA_SKIN, "");
        builder.define(DATA_STATUS, "");
        builder.define(DATA_HELD_ITEM, ItemStack.EMPTY);
        builder.define(DATA_SWING, 0);
        builder.define(DATA_STRENGTH, 0);
        builder.define(DATA_INTELLIGENCE, 0);
        builder.define(DATA_AGILITY, 0);
    }

    public double getSyncedHunger() {
        return entityData.get(DATA_HUNGER);
    }

    public String getSyncedSkin() {
        return entityData.get(DATA_SKIN);
    }

    public String getSyncedStatus() {
        return entityData.get(DATA_STATUS);
    }

    public void setHeldItem(ItemStack stack) {
        ItemStack value = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.isSameItemSameComponents(entityData.get(DATA_HELD_ITEM), value)) {
            return;
        }
        setItemInHand(InteractionHand.MAIN_HAND, value);
        entityData.set(DATA_HELD_ITEM, value);
    }

    public void playSwing() {
        entityData.set(DATA_SWING, entityData.get(DATA_SWING) + 1);
    }

    /** 把天赋三项同步给客户端；值没变时 SynchedEntityData 不打包，可以放心每 tick 调。 */
    public void syncTalent(NpcData data) {
        if (data == null) {
            return;
        }
        entityData.set(DATA_STRENGTH, data.getStrength());
        entityData.set(DATA_INTELLIGENCE, data.getIntelligence());
        entityData.set(DATA_AGILITY, data.getAgility());
    }

    public int getSyncedStrength() {
        return entityData.get(DATA_STRENGTH);
    }

    public int getSyncedIntelligence() {
        return entityData.get(DATA_INTELLIGENCE);
    }

    public int getSyncedAgility() {
        return entityData.get(DATA_AGILITY);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_HELD_ITEM.equals(key)) {
            setItemInHand(InteractionHand.MAIN_HAND, entityData.get(DATA_HELD_ITEM));
            return;
        }
        if (DATA_SWING.equals(key) && level().isClientSide()) {
            swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (citizenId != null) {
            tag.putUUID(KEY_CITIZEN_ID, citizenId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_CITIZEN_ID)) {
            citizenId = tag.getUUID(KEY_CITIZEN_ID);
        }
        loadInventory();
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
        loadInventory();
    }

    public NpcInventory getNpcInventory() {
        return npcInventory;
    }

    private NpcInventory createInventory() {
        NpcInventory inventory = new NpcInventory();
        inventory.setChangeListener(changed -> saveInventory());
        return inventory;
    }

    private void saveInventory() {
        if (inventoryLoading || level() == null || level().isClientSide() || citizenId == null) {
            return;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(citizenId);
        if (data == null) {
            return;
        }
        data.setInventoryJson(npcInventory.writeToSnbt(level().registryAccess()));
        data.markDirty();
    }

    private void loadInventory() {
        if (level() == null || level().isClientSide() || citizenId == null) {
            return;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(citizenId);
        inventoryLoading = true;
        try {
            npcInventory.readFromSnbt(level().registryAccess(), data == null ? null : data.getInventoryJson());
        } finally {
            inventoryLoading = false;
        }
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (!level().isClientSide()) {
            purgeCitizenData();
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        stopConstructionPath();
        if (!level().isClientSide() && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            purgeCitizenData();
        }
    }

    private void purgeCitizenData() {
        UUID id = citizenId;
        if (id == null) {
            return;
        }
        citizenId = null;
        clearSocialPartner();
        if (isSitting()) {
            setSitting(false);
        }
        NpcManager.INSTANCE.removeNpc(id);
    }

    public boolean isSitting() {
        return entityData.get(DATA_SITTING);
    }

    /** 就餐/就座流程要从外部让人坐下，所以是 public。 */
    public void setSitting(boolean sitting) {
        entityData.set(DATA_SITTING, sitting);
    }

    public static void onJoinWorld(NpcEntity entity) {
        if (entity.level() == null || entity.level().isClientSide()) {
            return;
        }
        NpcData data = entity.citizenId == null ? null : NpcManager.INSTANCE.getNpc(entity.citizenId);
        if (data == null) {
            return;
        }
        data.setDimension(entity.level().dimension().location().toString());
        data.setPosition(entity.getX(), entity.getY(), entity.getZ());
        KingdomPersistence.submit(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID, data.getId().toString(), data.toValues());
        data.clearDirty();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data == null) {
            stopSitting();
            getNavigation().stop();
            return;
        }
        entityData.set(DATA_HUNGER, (float) data.getHunger());
        entityData.set(DATA_SKIN, data.getSkin());
        entityData.set(DATA_STATUS, data.getDetail().label());
        if (data.getEntityUuid() == null) {
            data.setEntityUuid(getUUID());
        }
        data.setDimension(dimensionId());
        data.setPosition(getX(), getY(), getZ());
        syncTalent(data);
        NpcBrain brain = data.getBrain();
        if (brain.getState() == NpcState.COMMANDED) {
            tickRtsCommand();
            return;
        }
        NpcDetail detail = brain.getDetail();
        if (detail != activeDetail) {
            activeDetail = detail;
            onDetailEnter();
        }
        switch (detail) {
            case BUILDING -> {
            }
            case FORAGING -> {
                stopSitting();
                NpcForageService.tick(this, data);
            }
            case DINING -> {
                // 就餐中不能收坐姿：坐下来等菜正是这个分支要做的事
                NpcForageService.tick(this, data);
            }
            case FARMING, PLANTING, HARVESTING -> {
                stopSitting();
                NpcFarmService.tick(this, data);
            }
            case PRODUCING -> {
                stopSitting();
                stayAt(data.getWorkX(), data.getWorkY(), data.getWorkZ());
                NpcIndustryService.tick(this, data);
            }
            case COOKING -> {
                stopSitting();
                stayAt(data.getWorkX(), data.getWorkY(), data.getWorkZ());
                NpcRestaurantService.tick(this, data);
            }
            case SERVING -> {
                stopSitting();
                NpcRestaurantService.tickWaiter(this, data);
            }
            case RANCHING -> stopSitting();
            case GOING_WORK -> {
                // 自定义 A*：原版导航爬不了台阶与多层建筑，上班路上会被墙卡住
                tickPathTo(new BlockPos(data.getWorkX(), data.getWorkY(), data.getWorkZ()));
            }
            case GOING_HOME -> {
                // 同上，回家也走自定义 A*
                tickPathTo(new BlockPos(data.getHomeX(), data.getHomeY(), data.getHomeZ()));
            }
            case WORKING_FOCUS -> stayAt(data.getWorkX(), data.getWorkY(), data.getWorkZ());
            case SLACKING -> {
                stopSitting();
                wanderAround(data.getWorkX(), data.getWorkY(), data.getWorkZ());
            }
            case SLEEPING -> sleepAtHome(data);
            case WANDERING -> executeWander(brain);
            default -> {
                stopSitting();
                getNavigation().stop();
            }
        }
        // 只有"随机走动"这类 detail 才需要 NpcPathRetry 兜底 —— 它卡住 100 tick 后
        // 会让人随机走一个点。而上班/回家/睡觉/做工/农活走的都是自定义多层 A*
        // （tickPathTo），那边自带 trackWorkStuck + escalateWork（含传送保底）；
        // 两套同时插手的结果是刚规划好的跨层路径被"随机走一个点"冲掉，
        // 表现就是人走到楼梯一半忽然往回走。
        if (detail == NpcDetail.WANDERING || detail == NpcDetail.SLACKING) {
            pathRetry.tick(this);
        }
    }

    private void onDetailEnter() {
        sitWalking = false;
        socialized = false;
        activeStyle = null;
        clearSocialPartner();
        forageTarget = null;
        // 换行为就一定要起床：作息窗口（服务端按世界时间调度）只会改 detail，
        // 这里若不同步收掉睡姿，NPC 从 SLEEPING 切到上班后会一直躺着不动。
        if (activeDetail != NpcDetail.SLEEPING && isSleeping()) {
            stopSleeping();
        }
        if (!isFarmDetail(activeDetail)) {
            farmTarget = null;
            ranchTarget = null;
            clearNavTarget();
            getNavigation().stop();
        }
    }

    private static boolean isFarmDetail(NpcDetail detail) {
        return detail == NpcDetail.FARMING || detail == NpcDetail.PLANTING || detail == NpcDetail.HARVESTING;
    }

    private void executeWander(NpcBrain brain) {
        NpcBrain.WanderStyle style = brain.getWanderStyle();
        if (style != activeStyle) {
            activeStyle = style;
            sitWalking = false;
            socialized = false;
            clearSocialPartner();
            getNavigation().stop();
            clearNavTarget();
        }
        switch (style) {
            case SOCIALIZE -> socialize();
            case SIT -> sitBehavior();
            default -> {
                if (navTarget == null || nearTarget(navTarget, ARRIVE_SQR)) {
                    walkToRandomPoint(WANDER_RADIUS);
                }
            }
        }
        if (style != NpcBrain.WanderStyle.SIT) {
            stopSitting();
        }
    }

    /**
     * 站到某个点待命（工位、灶台）。
     * <p>
     * 必须和上下班（GOING_WORK / GOING_HOME / SLEEPING）一样走自定义多层 A*。
     * 工位常常在楼上 —— 二层厂房、带楼梯的餐厅 —— 而原版 {@code getNavigation()}
     * 只有单层，且按 {@code maxUpStep} 判断台阶能不能上：碰上楼梯它要么干脆算不出路，
     * 要么给出一条走两步就断的绕行路，于是人走到楼梯一半又退回来、最后卡在门口原地转圈。
     * 自定义 A* 不看 maxUpStep，靠 {@link #assistClimb} 主动起跳补足高差。
     * <p>
     * 农业一直没这个问题，正是因为它的 FARMING 走 {@code setFarmTarget -> tickPathTo}。
     */
    private void stayAt(int x, int y, int z) {
        BlockPos target = new BlockPos(x, y, z);
        if (nearTarget(target, WORK_ARRIVE_SQR)) {
            getNavigation().stop();
            clearNavTarget();
            return;
        }
        stopSitting();
        tickPathTo(target);
    }

    private void wanderAround(int x, int y, int z) {
        if (navTarget == null || nearTarget(navTarget, ARRIVE_SQR)) {
            walkToRandomPoint(new BlockPos(x, y, z), WANDER_RADIUS);
        }
    }

    private void sleepAtHome(NpcData data) {
        if (data.getHomeId() == null) {
            getNavigation().stop();
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 走到自己那张床再躺下（原版 LivingEntity#startSleeping 的姿势/占用逻辑全都能用）。
        BlockPos bed = HomeBeds.assignedBed(serverLevel, data);
        if (bed == null) {
            // 床被拆了：解除住宅关联，这个人从此无家可归，等玩家重新分配。
            HomeBeds.unbind(data);
            if (isSleeping()) {
                stopSleeping();
            }
            getNavigation().stop();
            return;
        }
        BlockPos stand = bed.above();
        if (nearTarget(stand, HOME_ARRIVE_SQR)) {
            getNavigation().stop();
            clearNavTarget();
            if (isSitting()) {
                setSitting(false);
            }
            if (!isSleeping()) {
                startSleeping(bed);
            }
            return;
        }
        stopSitting();
        if (isSleeping()) {
            stopSleeping();
        }
        // 走到床边同样用自定义 A*：卧室多在楼上，原版导航上不去
        tickPathTo(stand);
    }

    /**
     * 是否已站到目标点（水平到位 + 高度对得上）。
     * <p>
     * 高度这一项不能省：多层建筑里楼上楼下常常是同一个 XZ，只比水平距离的话，
     * 人站在二楼就会被判成"已经到了一楼的目标"，寻路随即停下，
     * 而后续逻辑又发现够不着真正的东西，于是反复重算 —— 表现就是原地转圈。
     * 容差与 {@link #tickPathTo} 里的一致（1.5 格）。
     */
    private boolean nearTarget(BlockPos target, double arriveSqr) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= arriveSqr && Math.abs(getY() - target.getY()) < 1.5D;
    }

    private void clearNavTarget() {
        navTarget = null;
        pathRetry.reset();
    }

    public boolean walkToRandomPoint(double radius) {
        return walkToRandomPoint(blockPosition(), radius);
    }

    public boolean walkToRandomPoint(BlockPos center, double radius) {
        for (int i = 0; i < 8; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist = random.nextDouble() * radius;
            double tx = center.getX() + 0.5D + Math.cos(angle) * dist;
            double tz = center.getZ() + 0.5D + Math.sin(angle) * dist;
            BlockPos target = BlockPos.containing(tx, center.getY(), tz);
            if (getNavigation().isStableDestination(target)) {
                getNavigation().moveTo(tx, center.getY(), tz, WALK_SPEED);
                setNavTarget(target);
                return true;
            }
        }
        return false;
    }

    private void acquireSocialPartner() {
        NpcEntity candidate = findSocialPartner();
        if (candidate == null || candidate.socialPartner != null) {
            return;
        }
        socialPartner = candidate;
        candidate.socialPartner = this;
        setNavTarget(candidate.blockPosition());
    }

    private void clearSocialPartner() {
        NpcEntity partner = socialPartner;
        socialPartner = null;
        if (partner != null && partner.socialPartner == this) {
            partner.socialPartner = null;
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
        if (distanceToSqr(partner) > gap * gap) {
            socialized = false;
            if (!getNavigation().isInProgress()) {
                double dx = getX() - partner.getX();
                double dz = getZ() - partner.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                double tx = partner.getX() + (len > 1.0E-4 ? dx / len * SOCIALIZE_GAP : SOCIALIZE_GAP);
                double tz = partner.getZ() + (len > 1.0E-4 ? dz / len * SOCIALIZE_GAP : SOCIALIZE_GAP);
                getNavigation().moveTo(tx, getY(), tz, WALK_SPEED);
            }
        } else {
            getNavigation().stop();
            getLookControl().setLookAt(partner, 30.0F, 30.0F);
            if (!socialized) {
                socialized = true;
                spawnConversationParticle();
            }
        }
    }

    private NpcEntity findSocialPartner() {
        List<NpcEntity> nearby = level().getEntitiesOfClass(NpcEntity.class, getBoundingBox().inflate(SOCIALIZE_RADIUS), this::isSocialPartner);
        NpcEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (NpcEntity candidate : nearby) {
            double dist = distanceToSqr(candidate);
            if (dist < best) {
                best = dist;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private boolean isSocialPartner(NpcEntity other) {
        if (other == this || !other.isAlive() || other.citizenId == null) {
            return false;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(other.citizenId);
        if (data == null) {
            return false;
        }
        NpcBrain brain = data.getBrain();
        return brain.getDetail() == NpcDetail.WANDERING
                && brain.getWanderStyle() == NpcBrain.WanderStyle.SOCIALIZE;
    }

    private void spawnConversationParticle() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ParticleOptions particle = random.nextBoolean() ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER;
        serverLevel.sendParticles(particle, getX(), getY() + getEyeHeight(), getZ(), 6, 0.3D, 0.3D, 0.3D, 0.0D);
    }

    private void sitBehavior() {
        if (isSitting()) {
            getNavigation().stop();
            return;
        }
        if (!sitWalking) {
            sitWalking = true;
            walkToRandomPoint(SIT_WALK_RADIUS);
        } else if (getNavigation().isDone()) {
            sitWalking = false;
            setSitting(true);
        }
    }

    private void stopSitting() {
        if (isSitting()) {
            setSitting(false);
        }
    }
}
