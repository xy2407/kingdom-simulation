package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.xy2407.kingdom.common.npc.NpcBackpackMenu;
import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcDetail;
import com.xy2407.kingdom.common.npc.NpcForageService;
import com.xy2407.kingdom.common.npc.NpcInventory;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcState;
import com.xy2407.kingdom.common.path.NpcPathRetry;
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
 * 市民实体，按状态机闲逛决策执行游走/社交/坐下
 */
public class NpcEntity extends PathfinderMob {
    private static final String KEY_CITIZEN_ID = "CitizenId";
    private static final EntityDataAccessor<Boolean> DATA_SITTING =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * 饥饿值同步给客户端。
     *
     * <p>饥饿只存在于服务端的 {@code NpcData} 里，客户端光凭实体读不到（身份卡片就是纯客户端
     * 拼的，所以它的饥饿一直是"无"）。这里做成同步数据，客户端随时读到最新值。</p>
     */
    private static final EntityDataAccessor<Float> DATA_HUNGER =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.FLOAT);
    /** 皮肤名（贴图文件名，不含扩展名）；空串表示用默认皮肤。 */
    private static final EntityDataAccessor<String> DATA_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final double WANDER_RADIUS = 10.0D;
    private static final double SIT_WALK_RADIUS = 4.0D;

    /** 24 格随身行装；对外通过 {@code Capabilities.ItemHandler.ENTITY} 暴露给 AUI 容器菜单。 */
    private final NpcInventory npcInventory = createInventory();
    /** 载入行装期间抑制落库回调，避免"读出来的旧数据又被标脏"。 */
    private boolean inventoryLoading;
    private static final double SOCIALIZE_RADIUS = 12.0D;
    private static final double SOCIALIZE_GAP = 1.0D;
    private static final double WALK_SPEED = 0.5D;
    private static final double ARRIVE_SQR = 1.0D;
    private static final double WORK_ARRIVE_SQR = 4.0D;
    private static final double HOME_ARRIVE_SQR = 4.0D;
    private static final int STATIONARY_TRAVEL_INTERVAL = 20;
    private static final int RTS_REPATH_INTERVAL = 40;
    private static final int RTS_SEARCH_RETRY_INTERVAL = 5;
    private static final double RTS_DENSE_ARRIVE_SQR = 0.5625D;
    private static final double RTS_CROWD_ARRIVE_SQR = 2.25D;
    private static final double RTS_ARRIVE_HEIGHT = 3.0D;
    private static final int WORK_STUCK_TICKS = 40;
    private static final int WORK_STUCK_LIMIT = 3;
    private static final int WORK_SEARCH_FAIL_LIMIT = 8;
    private static final int WORK_PATH_TIMEOUT_TICKS = 200;
    private static final double WORK_FLOOR_MIN_Y = 0.45D;
    private static final double WORK_HEADROOM_MAX_Y = 0.1D;

    /**
     * 远离所有玩家的 NPC 用几倍时间步长积分。
     *
     * <p>NPC 数量上到数千时，{@code Entity.move()} 的碰撞扫描是所有实体里最贵的单项
     * （profiler 里 travel 能占到将近一半）。让"附近没有任何玩家"的 NPC 每 N tick 才走一次、
     * 同时把位移速度乘 N，总位移不变：俯视下看不出差别，碰撞扫描次数直接降到 1/N。</p>
     *
     * <p>用 {@code getId()} 错开相位，避免同一 tick 所有 NPC 一起更新造成周期性峰值。</p>
     */
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
    private BlockPos navTarget;
    private final NpcPathRetry pathRetry = new NpcPathRetry();
    private BlockPos rtsTarget;
    private boolean rtsClearOnArrive;
    private boolean rtsHighPath;
    private double rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
    private int rtsRepathCooldown;
    private Future<List<BlockPos>> pendingPath;
    private final ArrayDeque<BlockPos> rtsWaypoints = new ArrayDeque<>();
    private Future<List<BlockPos>> workPathFuture;
    private int workRepathCooldown;
    private int workPathDeadline;
    private BlockPos workStandSource;
    private BlockPos workStand;
    private BlockPos workAnchor;
    private int workStuckTicks;
    private int workFailures;

    public void setRtsTarget(BlockPos target, boolean clearOnArrive) {
        rtsWaypoints.clear();
        applyRtsTarget(target, clearOnArrive, false);
    }

    /** 自定义 A* 寻路的 RTS 目标，路径在后台线程计算后回主线程执行 */
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
        // 终点是方阵里分到的格子，邻居贴身，判定必须比独占一格时宽，否则会被挤到永远差半格
        rtsArriveSqr = RTS_CROWD_ARRIVE_SQR;
        // 走自定义 A*：路径本身由 applyPath 直接塞给导航（不触发原版寻路），
        // 重试也必须走同一条路，否则每次重试都会在主线程跑一遍原版 A*。
        rtsHighPath = true;
        rtsRepathCooldown = 0;
        clearPendingPath();
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

    private void applyRtsTarget(BlockPos target, boolean clearOnArrive, boolean highPath) {
        rtsTarget = target;
        rtsClearOnArrive = clearOnArrive;
        rtsHighPath = highPath;
        rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
        rtsRepathCooldown = 0;
        clearPendingPath();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        if (highPath) {
            getNavigation().stop();
        } else {
            getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
        }
        setNavTarget(target);
    }

    private void clearPendingPath() {
        Future<List<BlockPos>> pending = pendingPath;
        pendingPath = null;
        if (pending != null) {
            pending.cancel(false);
        }
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
            clearPendingPath();
            if (rtsClearOnArrive) {
                discard();
                return;
            }
            stopSitting();
            getNavigation().stop();
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

    /** 自定义 A* 在后台线程计算，结果回主线程后再交给原版导航执行 */
    private void tickHighPath(BlockPos target) {
        if (getNavigation().isInProgress()) {
            rtsRepathCooldown = 0;
            return;
        }
        Future<List<BlockPos>> pending = pendingPath;
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            pendingPath = null;
            List<BlockPos> waypoints = takePath(pending);
            if (waypoints != null && waypoints.size() > 1) {
                rtsRepathCooldown = RTS_REPATH_INTERVAL;
                applyPath(waypoints);
            } else {
                rtsRepathCooldown = RTS_SEARCH_RETRY_INTERVAL;
            }
            return;
        }
        if (rtsRepathCooldown > 0) {
            rtsRepathCooldown--;
            return;
        }
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return;
        }
        Future<List<BlockPos>> future = repository.submitSearch(level, blockPosition(), target);
        if (future == null) {
            rtsRepathCooldown = RTS_SEARCH_RETRY_INTERVAL;
            return;
        }
        pendingPath = future;
    }

    private List<BlockPos> takePath(Future<List<BlockPos>> future) {
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

    /**
     * 施工走位：由施工调度每 tick 驱动，用自定义 A* 异步寻路到站位。
     * 到达站位后停住并返回 true，调度以此作为放置触发条件。
     */
    public boolean tickConstructionPath(BlockPos target) {
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
            if (!workPathFuture.isDone() && tickCount < workPathDeadline) {
                return false;
            }
            Future<List<BlockPos>> future = workPathFuture;
            workPathFuture = null;
            List<BlockPos> waypoints = future.isDone() ? takePath(future) : null;
            future.cancel(false);
            if (waypoints != null && waypoints.size() > 1) {
                workRepathCooldown = 20;
                workFailures = 0;
                applyPath(waypoints);
            } else {
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
        Future<List<BlockPos>> future = repository.submitSearch(level, blockPosition(), stand);
        if (future == null) {
            workRepathCooldown = 10;
            escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
            return false;
        }
        workPathFuture = future;
        workPathDeadline = tickCount + WORK_PATH_TIMEOUT_TICKS;
        return false;
    }

    /** 停止施工走位（到达站位、任务结束或实体移除时调用） */
    public void stopConstructionPath() {
        if (workPathFuture != null) {
            workPathFuture.cancel(false);
            workPathFuture = null;
        }
        workRepathCooldown = 0;
        workPathDeadline = 0;
        workStandSource = null;
        workStand = null;
        workAnchor = null;
        workStuckTicks = 0;
        workFailures = 0;
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
        workPathDeadline = 0;
        getNavigation().stop();
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
        Path path = getNavigation().getPath();
        Node node = path == null || path.isDone() ? null : path.getNextNode();
        if (node != null && node.y > getBlockY() && (onGround() || onClimbable())) {
            getJumpControl().jump();
        }
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
        getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
        setNavTarget(target);
    }

    public void clearForageTarget() {
        if (forageTarget == null) {
            return;
        }
        forageTarget = null;
        navTarget = null;
        getNavigation().stop();
        pathRetry.reset();
    }

    public void haltForage() {
        clearForageTarget();
        navTarget = null;
        getNavigation().stop();
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

    /**
     * 空手右键 = 打开该市民的行装界面；其余情况交还原版。
     *
     * <p>刻意排除潜行与副手：潜行是"我不想触发交互"的通用约定，副手留给原版逻辑，
     * 这样不会和别的模组抢同一次右键。</p>
     */
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

    /** 施工期间从脚手架跌落不掉血。 */
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

    /**
     * 当前该用几倍时间步长积分：附近没有玩家时返回 {@link #FAR_TRAVEL_STRIDE}，否则 1。
     *
     * <p>结果每 {@link #FAR_TRAVEL_RECHECK_TICKS} tick 重算一次，{@code getNearestPlayer} 的
     * 遍历成本被摊薄到可忽略。</p>
     */
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
    }

    /** 客户端可读的饥饿值（0 ~ {@link NpcData#HUNGER_MAX}）。 */
    public double getSyncedHunger() {
        return entityData.get(DATA_HUNGER);
    }

    /** 客户端可读的皮肤名；空串表示用默认皮肤。 */
    public String getSyncedSkin() {
        return entityData.get(DATA_SKIN);
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

    /** 24 格行装（也作为实体物品能力的数据源）。 */
    public NpcInventory getNpcInventory() {
        return npcInventory;
    }

    private NpcInventory createInventory() {
        NpcInventory inventory = new NpcInventory();
        inventory.setChangeListener(changed -> saveInventory());
        return inventory;
    }

    /**
     * 把行装写回市民数据。
     *
     * <p>玩家在 AUI 容器里点一下槽位只会改动 {@link NpcInventory}，真正的持久化是这里：
     * 序列化成 SNBT 塞进 {@link NpcData} 并标脏，由 {@code NpcManager.flushDirty} 批量落库。
     * 不能只依赖实体 NBT——市民实体是 shell，重建时不会带上 NBT。</p>
     */
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

    /** 从市民数据读回行装（实体生成/重建时调用）。 */
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

    private void setSitting(boolean sitting) {
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
        // 把只存在于服务端的属性同步给客户端：身份卡片要读饥饿，头像/模型要读皮肤。
        // SynchedEntityData 只在值变化时发包，所以逐 tick 写没有额外开销。
        entityData.set(DATA_HUNGER, (float) data.getHunger());
        entityData.set(DATA_SKIN, data.getSkin());
        if (data.getEntityUuid() == null) {
            data.setEntityUuid(getUUID());
        }
        data.setDimension(dimensionId());
        data.setPosition(getX(), getY(), getZ());
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
            case GOING_WORK -> {
                stopSitting();
                navigateTo(data.getWorkX(), data.getWorkY(), data.getWorkZ(), WORK_ARRIVE_SQR);
            }
            case GOING_HOME -> {
                stopSitting();
                navigateTo(data.getHomeX(), data.getHomeY(), data.getHomeZ(), HOME_ARRIVE_SQR);
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
        if (detail != NpcDetail.BUILDING) {
            pathRetry.tick(this);
        }
    }

    private void onDetailEnter() {
        sitWalking = false;
        socialized = false;
        activeStyle = null;
        clearSocialPartner();
        forageTarget = null;
        clearNavTarget();
        getNavigation().stop();
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

    private void navigateTo(int x, int y, int z, double arriveSqr) {
        BlockPos target = new BlockPos(x, y, z);
        if (nearTarget(target, arriveSqr)) {
            getNavigation().stop();
            clearNavTarget();
            return;
        }
        if (!target.equals(navTarget)) {
            getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
            setNavTarget(target);
        }
    }

    private void stayAt(int x, int y, int z) {
        if (nearTarget(new BlockPos(x, y, z), WORK_ARRIVE_SQR)) {
            getNavigation().stop();
            clearNavTarget();
            return;
        }
        stopSitting();
        navigateTo(x, y, z, WORK_ARRIVE_SQR);
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
        BlockPos home = new BlockPos(data.getHomeX(), data.getHomeY(), data.getHomeZ());
        if (nearTarget(home, HOME_ARRIVE_SQR)) {
            getNavigation().stop();
            clearNavTarget();
            if (!isSitting()) {
                setSitting(true);
            }
            return;
        }
        stopSitting();
        navigateTo(data.getHomeX(), data.getHomeY(), data.getHomeZ(), HOME_ARRIVE_SQR);
    }

    private boolean nearTarget(BlockPos target, double arriveSqr) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= arriveSqr;
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
