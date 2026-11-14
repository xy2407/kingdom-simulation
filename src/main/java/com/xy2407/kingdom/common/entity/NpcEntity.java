package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.xy2407.kingdom.common.block.WoodenChairBlock;
import com.xy2407.kingdom.common.combat.CombatWeapons;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugBroadcast;
import com.xy2407.kingdom.common.network.npc.NpcRemovalPacket;
import com.xy2407.kingdom.server.lumber.NpcLumberService;
import com.xy2407.kingdom.server.rts.RtsOpsService;
import com.xy2407.kingdom.server.rts.RtsSelectionService;
import com.xy2407.kingdom.common.npc.*;
import com.xy2407.kingdom.common.path.*;
import com.xy2407.kingdom.server.path.RoadRouteService;
import com.xy2407.kingdom.server.task.BuilderBoardService;
import com.xy2407.kingdom.server.task.RegionTaskService;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 市民实体，按状态机闲逛决策执行游走/社交/坐下。
 */
public class NpcEntity extends PathfinderMob {
    private static final String KEY_CITIZEN_ID = "CitizenId";
    /** 力量随实体一起存：属性基础值不进 NBT，重载时得靠它先把血量上限还原，别等数据库。 */
    private static final String KEY_STRENGTH = "KingdomStrength";
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
    private static final EntityDataAccessor<Integer> DATA_STRENGTH =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_INTELLIGENCE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AGILITY =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_TOURIST =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_OWNER_ID =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_TERRITORY_OWNER =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

    private final NpcInventory npcInventory = createInventory();
    private boolean inventoryLoading;
    private static final double WALK_SPEED = 0.5D;
    private static final double BASE_ATTACK_DAMAGE = 2.0D;
    private static final double BASE_MAX_HEALTH = 20.0D;
    /** 力量是百分比：100 力量 = +100% 基础值。 */
    private static final double STRENGTH_PERCENT = 100.0D;
    private static final double ARRIVE_SQR = 1.0D;
    private static final double WORK_ARRIVE_SQR = 1.0D;
    private static final double HOME_ARRIVE_SQR = 1.0D;
    private static final int WORK_STUCK_TICKS = 40;
    private static final int WORK_STUCK_LIMIT = 3;
    private static final int WORK_SEARCH_FAIL_LIMIT = 8;
    private static final int SEGMENT_STALE_LIMIT = 300;
    private static final int LOCAL_RECONNECT_COOLDOWN_TICKS = 60;
    private static final int LOCAL_RECONNECT_FAIL_TICKS = 100;
    private static final int BUILDER_REPATH_INTERVAL = 20;
    private static final long WORK_PATH_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int BUILDER_STUCK_TELEPORT_TICKS = 160;
    private static final double[] SLIDE_OFFSETS = {0.25D, 0.5D, 0.75D, 1.0D};
    private static final double CORNER_ARRIVE = 0.35D;
    private static final double PASSED_MAX_SQR = 4.0D;
    private static final double SLIDE_FORWARD = 0.3D;
    private static final int SLIDE_DURATION_TICKS = 8;
    private static final int SLIDE_COOLDOWN_TICKS = 30;
    private static final double RESYNC_TRIGGER_DISTANCE = 3.0D;

    private static final int FAR_TRAVEL_STRIDE = 2;
    private static final double FAR_TRAVEL_RADIUS = 64.0D;
    private static final int FAR_TRAVEL_RECHECK_TICKS = 40;

    private int farTravelStrideCache = 1;
    private int farTravelCheckTimer;

    private UUID citizenId;
    private NpcDetail activeDetail;
    private final NpcIdleBehavior idle = new NpcIdleBehavior(this);
    private ResourceKey<Level> cachedDimension;
    private String cachedDimensionId;
    private final NpcVerticalMovement vertical = new NpcVerticalMovement(this);
    private BlockPos forageTarget;
    private BlockPos farmTarget;
    private BlockPos ranchTarget;
    private BlockPos navTarget;
    private BlockPos seatPos;
    private final NpcPathRetry pathRetry = new NpcPathRetry();
    private final NpcRtsController rts = new NpcRtsController(this);
    private final NpcCombatController combat = new NpcCombatController(this);
    /** 战斗指令（坚守/进攻）是否已经从存档还原过：只在首次拿到市民数据时做一次，避免和后续的巡逻等临时态打架。 */
    private boolean combatOrderRestored;
    private Future<NpcPathfinder.PathResult> workPathFuture;
    private boolean workPathJoined;
    private NpcPathfinder.Session workSession;
    private List<NpcPathfinder.Waypoint> followPath = List.of();
    private int followIndex;
    private boolean followCompleted;
    private BlockPos followEndPos;
    private boolean testFollowing;
    /** 客户端专用：最近一次挥击动画的起始 tick，供模型摆动手臂用（原版 attackTime 在这个模型上不生效）。 */
    private int swingAnimStart = -100;
    private long sharedPathUsedKey = Long.MIN_VALUE;
    private int workRepathCooldown;
    private long workPathDeadlineNanos;
    private BlockPos workStandSource;
    private BlockPos workStand;
    private BlockPos segmentTarget;
    private int segmentStaleTicks;
    private double segmentBestDistance = Double.MAX_VALUE;
    private BlockPos workAnchor;
    private int workStuckTicks;
    private int workFailures;
    private Vec3 slideTarget;
    private int slideTicks;
    private int slideCooldown;
    private int localReconnectCooldown;
    private int builderRepathCooldown;
    private int pathPaddingStage;
    private int builderStuckTicks;
    private double builderStuckBestDist = Double.MAX_VALUE;
    private BlockPos builderStuckTarget;
    private double builderAnchorX;
    private double builderAnchorY;
    private double builderAnchorZ;
    /** 建筑工异步寻路（与普通 NPC 同一套线程池） */
    private Future<NpcPathfinder.PathResult> builderPathFuture;
    /** 本 tick 是否模拟玩家按跳跃（原版攀爬），以及方向 */
    private boolean climbInput;
    private boolean climbUp;


    public void setRtsTarget(BlockPos target, boolean clearOnArrive) {
        rts.setTarget(target, clearOnArrive);
    }

    public void setHighRtsTarget(BlockPos target, boolean clearOnArrive) {
        setRtsTarget(target, clearOnArrive);
    }

    public void setRtsGroupTarget(BlockPos landing, BlockPos groupTarget, boolean clearOnArrive) {
        rts.setGroupTarget(landing, groupTarget, clearOnArrive);
    }

    public void setRtsPrecise(boolean precise) {
        rts.setPrecise(precise);
    }

    public void setRtsWaypoints(List<BlockPos> waypoints) {
        rts.setWaypoints(waypoints);
    }

    /**
     * 用预计算路点直接下达 RTS 指令：跳过寻路搜索，巡逻的缓存路线走这条通道。
     * 语义与 {@link #setRtsTarget} 一致，只是路径来源不同。
     */
    public void setRtsCachedPath(BlockPos target, List<NpcPathfinder.Waypoint> path) {
        rts.setCachedPath(target, path);
    }

    /** 当前正在跟随的路点（只读，内部已是不可变列表，别改）。 */
    public List<NpcPathfinder.Waypoint> currentPath() {
        return followPath;
    }

    public List<BlockPos> getRtsWaypoints() {
        return rts.waypoints();
    }

    public void clearRtsWaypoints() {
        rts.clearWaypoints();
    }

    public void clearMoveTargets() {
        forageTarget = null;
        farmTarget = null;
        ranchTarget = null;
        rts.clear();
        sharedPathUsedKey = Long.MIN_VALUE;
        clearNavTarget();
    }

    void resetSharedPathKey() {
        sharedPathUsedKey = Long.MIN_VALUE;
    }

    private NpcPathfinder.PathResult takeResult(Future<NpcPathfinder.PathResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        } catch (CancellationException e) {
            return null;
        }
    }

    public boolean tickConstructionPath(BlockPos target) {
        double adx = getX() - (target.getX() + 0.5D);
        double adz = getZ() - (target.getZ() + 0.5D);
        boolean closeEnough = adx * adx + adz * adz <= 4.0D
                && Math.abs(Math.floor(getY() + 0.5D) - target.getY()) <= 1.0D;
        if (closeEnough) {
            builderStuckTicks = 0;
            builderStuckBestDist = Double.MAX_VALUE;
            builderStuckTarget = null;
            followPath = List.of();
            followIndex = 0;
            cancelBuilderPath();
            return true;
        }
        if (followIndex < followPath.size()) {
            followWaypoints();
            checkBuilderStuck(target);
            return false;
        }
        if (builderPathFuture != null) {
            if (!builderPathFuture.isDone()) {
                checkBuilderStuck(target);
                return false;
            }
            Future<NpcPathfinder.PathResult> future = builderPathFuture;
            builderPathFuture = null;
            NpcPathfinder.PathResult result = takeResult(future);
            if (result != null && result.waypoints().size() > 1) {
                applyPath(result.waypoints());
                builderRepathCooldown = 0;
            } else {
                builderRepathCooldown = 2;
            }
            checkBuilderStuck(target);
            return false;
        }
        if (--builderRepathCooldown > 0) {
            checkBuilderStuck(target);
            return false;
        }
        builderRepathCooldown = BUILDER_REPATH_INTERVAL;
        if (level() instanceof ServerLevel serverLevel) {
            // 建筑工与普通 NPC 共用同一套快照 A*（提交到仓库线程池，不在主线程跑）
            SnapshotRepository repository = SnapshotRepository.get(serverLevel.getServer());
            if (repository != null) {
                NpcPathfinder.Session session =
                        new NpcPathfinder.Session(blockPosition(), target);
                builderPathFuture = repository.submitSessionSearch(serverLevel, session,
                        blockPosition(), target, NpcPathfinder.paddingOf(pathPaddingStage), false);
                if (builderPathFuture == null) {
                    builderRepathCooldown = 2;
                }
            }
        }
        checkBuilderStuck(target);
        return false;
    }

    private void cancelBuilderPath() {
        if (builderPathFuture != null) {
            builderPathFuture.cancel(false);
            builderPathFuture = null;
        }
    }

    boolean tickPathTo(BlockPos target) {
        return tickPathTo(target, true);
    }

    boolean tickPathTo(BlockPos target, boolean preferRoad) {
        stopSitting();
        BlockPos effective = target;
        BlockPos stand = resolveWorkStand(effective);
        stand = applySegment(stand);
        double dx = getX() - (stand.getX() + 0.5D);
        double dz = getZ() - (stand.getZ() + 0.5D);
        double arriveSqr = rts.arrivalThreshold();
        if (dx * dx + dz * dz < arriveSqr
                && Math.abs(Math.floor(getY() + 0.5D) - stand.getY()) < 1.0D) {
            stopConstructionPath();
            if (rts.withinHandoff(blockPosition())) {
                rts.clearGroupDest();
            }
            return true;
        }
        if (followIndex < followPath.size()) {
            followWaypoints();
            if (trackWorkStuck()) {
                if (tryLocalReconnect()) {
                    workStuckTicks = 0;
                } else if (trySlide()) {
                    workStuckTicks = 0;
                } else {
                    escalateWork(stand, WORK_STUCK_LIMIT);
                }
            }
            return false;
        }
        BlockPos group = rts.groupDest();
        if (group != null && followCompleted) {
            double gdx = getX() - (group.getX() + 0.5D);
            double gdz = getZ() - (group.getZ() + 0.5D);
            boolean atAnchor = followEndPos != null
                    && followEndPos.getX() == stand.getX()
                    && followEndPos.getZ() == stand.getZ()
                    && Math.abs(followEndPos.getY() - stand.getY()) <= 1;
            if (gdx * gdx + gdz * gdz < 9.0D || !atAnchor) {
                rts.clearGroupDest();
                stopConstructionPath();
                return false;
            }
        }
        workAnchor = null;
        workStuckTicks = 0;
        if (workPathFuture != null) {
            if (!workPathFuture.isDone() && System.nanoTime() < workPathDeadlineNanos) {
                return false;
            }
            Future<NpcPathfinder.PathResult> future = workPathFuture;
            workPathFuture = null;
            boolean joined = workPathJoined;
            workPathJoined = false;
            if (joined) {
                workRepathCooldown = 0;
                return false;
            }
            NpcPathfinder.PathResult result = future.isDone() ? takeResult(future) : null;
            future.cancel(false);
            List<NpcPathfinder.Waypoint> waypoints = result == null ? null : result.waypoints();
            if (waypoints != null && waypoints.size() > 1) {
                if (endpointMatches(waypoints, stand)) {
                    List<NpcPathfinder.Waypoint> trimmed = trimForLanding(waypoints);
                    if (trimmed.size() < waypoints.size()) {
                        sharedPathUsedKey = stand.asLong();
                    }
                    workRepathCooldown = rts.target() == null ? 20 : 0;
                    workFailures = 0;
                    pathPaddingStage = 0;
                    applyPath(trimmed);
                } else if (result.missingChunks() > 0) {
                    // 缺快照的惩罚从 10 tick 压到 1：快照在 ServerTickEvent.Post 构建，
                    // 请求当 tick 的 Post 就建好了，等 10 纯属空转——这就是静止 NPC
                    // 从收到指令到迈步要 1~2 秒的主因（移动中的 NPC 快照热，一次就中）。
                    workRepathCooldown = 1;
                } else {
                    pathPaddingStage++;
                    workRepathCooldown = 2;
                    escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
                }
            } else if (result != null && result.missingChunks() > 0) {
                workRepathCooldown = 1;
            } else {
                pathPaddingStage++;
                workRepathCooldown = 2;
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
        BlockPos from = blockPosition();
        Future<NpcPathfinder.PathResult> future = null;
        // 玩家硬指令走紧急寻路通道（独立配额+线程池），不被全城日常寻路排队挤掉
        boolean urgent = rts.isUrgentOrder();
        if (rts.target() != null) {
            RtsSharedPath.Join join = RtsSharedPath.get(level.getServer())
                    .joinOrSubmit(level, from, stand, NpcPathfinder.paddingOf(pathPaddingStage), getId(), urgent);
            if (join != null) {
                if (join.readyPath() != null) {
                    List<NpcPathfinder.Waypoint> trimmed = trimForLanding(join.readyPath());
                    if (trimmed.size() < join.readyPath().size()) {
                        sharedPathUsedKey = stand.asLong();
                    }
                    workRepathCooldown = rts.target() == null ? 20 : 0;
                    workFailures = 0;
                    pathPaddingStage = 0;
                    workSession = null;
                    applyPath(trimmed);
                    return false;
                }
                future = join.future();
                workPathJoined = join.shared();
                workSession = null;
            }
        }
        if (future == null && preferRoad) {
            List<NpcPathfinder.Waypoint> roadRoute = RoadRouteService.tryPlan(level, from, stand);
            if (roadRoute != null) {
                workRepathCooldown = 20;
                workFailures = 0;
                pathPaddingStage = 0;
                workSession = null;
                applyPath(roadRoute);
                return false;
            }
        }
        if (future == null) {
            // preferRoad 只决定「是否先试路网」，不能决定「能不能寻路」。
            // 以前写成 if (future == null && preferRoad)，于是所有传 false 的调用
            //（战斗追击、RTS 共享路径没命中时的兜底）永远提交不了寻路任务，
            // 表现就是原地不动、只把头转过去盯着目标。
            SnapshotRepository repository = SnapshotRepository.get(level.getServer());
            if (repository == null) {
                return false;
            }
            NpcPathfinder.Session session = workSession;
            if (session == null || !session.matches(from, stand)) {
                session = new NpcPathfinder.Session(from, stand);
                workSession = session;
            }
            session.preferRoad(preferRoad);
            future = repository.submitSessionSearch(level, session, from, stand,
                    NpcPathfinder.paddingOf(pathPaddingStage), urgent);
            workPathJoined = false;
        }
        if (future == null) {
            // 提交失败（本 tick 搜索配额被日常寻路吃光/仓库未就绪）：短冷却快速重试，
            // 以前等 10 tick，叠加起来就是玩家看到的"静止 NPC 响应要 2 秒"。
            workRepathCooldown = 2;
            escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
            return false;
        }
        workPathFuture = future;
        workPathDeadlineNanos = System.nanoTime() + WORK_PATH_TIMEOUT_NANOS;
        return false;
    }

    private void checkBuilderStuck(BlockPos target) {
        if (builderStuckTarget == null || !builderStuckTarget.equals(target)) {
            resetBuilderStuck(target);
            return;
        }
        double moved = (getX() - builderAnchorX) * (getX() - builderAnchorX)
                + (getY() - builderAnchorY) * (getY() - builderAnchorY)
                + (getZ() - builderAnchorZ) * (getZ() - builderAnchorZ);
        if (moved > 0.04D) {
            builderAnchorX = getX();
            builderAnchorY = getY();
            builderAnchorZ = getZ();
            builderStuckTicks = 0;
            double dx = getX() - (target.getX() + 0.5D);
            double dz = getZ() - (target.getZ() + 0.5D);
            double dy = Math.floor(getY() + 0.5D) - target.getY();
            builderStuckBestDist = Math.min(builderStuckBestDist, dx * dx + dz * dz + dy * dy);
            return;
        }
        if (++builderStuckTicks < BUILDER_STUCK_TELEPORT_TICKS) {
            return;
        }
        resetBuilderStuck(target);
        stopConstructionPath();
        setDeltaMovement(Vec3.ZERO);
        teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
    }

    private void resetBuilderStuck(BlockPos target) {
        builderStuckTarget = target == null ? null : target.immutable();
        builderStuckTicks = 0;
        builderStuckBestDist = Double.MAX_VALUE;
        builderAnchorX = getX();
        builderAnchorY = getY();
        builderAnchorZ = getZ();
    }

    public void stopConstructionPath() {
        if (workPathFuture != null) {
            workPathFuture.cancel(false);
            workPathFuture = null;
        }
        workPathJoined = false;
        followCompleted = false;
        followEndPos = null;
        workRepathCooldown = 0;
        workPathDeadlineNanos = 0L;
        vertical.resetCooldown();
        workStandSource = null;
        workStand = null;
        workAnchor = null;
        workStuckTicks = 0;
        workFailures = 0;
        pathPaddingStage = 0;
        workSession = null;
        cancelBuilderPath();
        builderRepathCooldown = 0;
        builderStuckTicks = 0;
        builderStuckBestDist = Double.MAX_VALUE;
        slideTarget = null;
        slideTicks = 0;
        slideCooldown = 0;
        localReconnectCooldown = 0;
        segmentTarget = null;
        followPath = List.of();
        followIndex = 0;
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

    private boolean tryLocalReconnect() {
        if (localReconnectCooldown > 0 || followPath.isEmpty()) {
            return false;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        List<NpcPathfinder.Waypoint> patched =
                NpcLocalRepath.reconnect(serverLevel, blockPosition(), followPath, followIndex);
        if (patched == null) {
            localReconnectCooldown = LOCAL_RECONNECT_FAIL_TICKS;
            return false;
        }
        localReconnectCooldown = LOCAL_RECONNECT_COOLDOWN_TICKS;
        applyPath(patched);
        return true;
    }

    private boolean trySlide() {
        if (slideTicks > 0 || slideCooldown > 0 || followIndex >= followPath.size()) {
            return false;
        }
        NpcPathfinder.Waypoint waypoint = followPath.get(followIndex);
        BlockPos pos = waypoint.pos();
        if (waypoint.move() == NpcPathfinder.Waypoint.Move.CLIMB) {
            slideTarget = new Vec3(pos.getX() + 0.5D, getY(), pos.getZ() + 0.5D);
            slideTicks = SLIDE_DURATION_TICKS;
            return true;
        }
        double dx = pos.getX() + 0.5D - getX();
        double dz = pos.getZ() + 0.5D - getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return false;
        }
        double ux = dx / length;
        double uz = dz / length;
        Vec3 reference = navTarget == null
                ? new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)
                : new Vec3(navTarget.getX() + 0.5D, navTarget.getY(), navTarget.getZ() + 0.5D);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double offset : SLIDE_OFFSETS) {
            for (int side = 1; side >= -1; side -= 2) {
                double tx = getX() + ux * SLIDE_FORWARD - uz * offset * side;
                double tz = getZ() + uz * SLIDE_FORWARD + ux * offset * side;
                if (!canOccupy(BlockPos.containing(tx, getY(), tz))) {
                    continue;
                }
                double distance = reference.distanceToSqr(tx, reference.y, tz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Vec3(tx, getY(), tz);
                }
            }
        }
        if (best == null) {
            return false;
        }
        slideTarget = best;
        slideTicks = SLIDE_DURATION_TICKS;
        return true;
    }

    public boolean canOccupy(BlockPos pos) {
        if (!level().isLoaded(pos) || !standsOn(pos.below())
                || !passable(pos) || !passable(pos.above())) {
            return false;
        }
        AABB shifted = getBoundingBox().move(pos.getX() + 0.5D - getX(),
                pos.getY() - getY(), pos.getZ() + 0.5D - getZ());
        return level().noCollision(this, shifted);
    }

    private void escalateWork(BlockPos stand, int limit) {
        workAnchor = null;
        workStuckTicks = 0;
        workRepathCooldown = 0;
        workPathDeadlineNanos = 0L;
        vertical.resetCooldown();
        getNavigation().stop();
        if (++workFailures < limit) {
            return;
        }
        workFailures = 0;
        pathPaddingStage = 0;
        setDeltaMovement(Vec3.ZERO);
    }

    private BlockPos applySegment(BlockPos stand) {
        if (segmentTarget != null && nearTarget(segmentTarget, WORK_ARRIVE_SQR)) {
            segmentTarget = null;
            segmentStaleTicks = 0;
            segmentBestDistance = Double.MAX_VALUE;
            workRepathCooldown = 0;
        }
        if (segmentTarget != null) {
            double distance = blockPosition().distSqr(segmentTarget);
            if (distance < segmentBestDistance - 0.01D) {
                segmentBestDistance = distance;
                segmentStaleTicks = 0;
            } else if (++segmentStaleTicks > SEGMENT_STALE_LIMIT) {
                segmentTarget = null;
                segmentStaleTicks = 0;
                segmentBestDistance = Double.MAX_VALUE;
                workRepathCooldown = 0;
            }
        }
        if (segmentTarget != null) {
            return segmentTarget;
        }
        BlockPos here = blockPosition();
        double spanX = stand.getX() - here.getX();
        double spanZ = stand.getZ() - here.getZ();
        if (spanX * spanX + spanZ * spanZ
                <= (double) NpcPathfinder.LONG_SEGMENT * NpcPathfinder.LONG_SEGMENT) {
            return stand;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return stand;
        }
        SnapshotRepository repository = SnapshotRepository.get(serverLevel.getServer());
        BlockPos midway = NpcPathfinder.midway(repository, serverLevel.dimension(),
                here, stand, NpcPathfinder.paddingOf(pathPaddingStage));
        if (midway == null) {
            return stand;
        }
        segmentTarget = midway;
        segmentStaleTicks = 0;
        segmentBestDistance = Double.MAX_VALUE;
        return segmentTarget;
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
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos candidate = target.above(dy);
            if (standable(candidate)) {
                return candidate;
            }
        }
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
        return ChunkSnapshot.isWalkableFloor(level().getBlockState(pos));
    }

    private boolean passable(BlockPos pos) {
        return ChunkSnapshot.isPassable(level().getBlockState(pos));
    }

    public void startTestFollow(List<NpcPathfinder.Waypoint> path) {
        rts.clear();
        stopConstructionPath();
        testFollowing = true;
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        applyPath(path);
    }

    private void tickTestFollow() {
        if (followIndex >= followPath.size()) {
            testFollowing = false;
            stopConstructionPath();
            NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
            if (data != null) {
                data.getBrain().setRtsControlled(false);
            }
            return;
        }
        followWaypoints();
    }

    private void followWaypoints() {
        vertical.tickCooldown();
        if (slideCooldown > 0) {
            slideCooldown--;
        }
        if (localReconnectCooldown > 0) {
            localReconnectCooldown--;
        }
        if (slideTicks > 0) {
            slideTicks--;
            if (slideTarget != null) {
                getMoveControl().setWantedPosition(slideTarget.x, slideTarget.y, slideTarget.z, WALK_SPEED);
            }
            if (slideTicks == 0) {
                slideTarget = null;
                slideCooldown = SLIDE_COOLDOWN_TICKS;
            }
            return;
        }
        resyncFollowIndex();
        climbInput = false;
        climbUp = false;
        while (followIndex < followPath.size()) {
            NpcPathfinder.Waypoint waypoint = followPath.get(followIndex);
            BlockPos pos = waypoint.pos();
            if (passedWaypoint(followIndex)) {
                followIndex++;
                continue;
            }
            boolean ladderStart = isLadderCell(pos) && (followIndex == 0
                    || !isLadderCell(followPath.get(followIndex - 1).pos()));
            if (ladderStart) {
                teleportOnLadder(pos);
            }
            double dx = getX() - (pos.getX() + 0.5D);
            double dz = getZ() - (pos.getZ() + 0.5D);
            double arrive = switch (waypoint.move()) {
                case CLIMB -> 1.15D;
                case WALK -> {
                    // 精确模式（阵型编辑）的最后一段收紧到 0.1；其余情况一律保持原行为
                    if (rts.isPrecise() && followIndex >= followPath.size() - 1) {
                        yield rts.lastHopRadius();
                    }
                    yield isCorner(followIndex) ? CORNER_ARRIVE : 0.5D;
                }
                default -> 1.0D;
            };
            if (dx * dx + dz * dz <= arrive * arrive
                    && Math.abs(Math.floor(getY() + 0.5D) - pos.getY()) < 1.0D) {
                boolean ladderEnd = isLadderCell(pos) && followIndex + 1 < followPath.size()
                        && !isLadderCell(followPath.get(followIndex + 1).pos());
                if (ladderEnd) {
                    teleportOffLadder(pos, followPath.get(followIndex + 1).pos());
                }
                followIndex++;
                continue;
            }
            boolean insideColumnNow =
                    ChunkSnapshot.isClimbableFloor(level().getBlockState(blockPosition()));
            double offsetX = Math.abs(getX() - (blockPosition().getX() + 0.5D));
            double offsetZ = Math.abs(getZ() - (blockPosition().getZ() + 0.5D));
            boolean climbingColumn = vertical.onClimbable() && insideColumnNow
                    && offsetX < 0.45D && offsetZ < 0.45D
                    && pos.getY() > getBlockY();
            if (climbingColumn) {
                climbInput = true;
                climbUp = true;
            } else {
                applyVertical(waypoint, climbDelta(followIndex));
            }
            tryOpenDoor(pos);
            autoOpenPassage(pos);
            boolean verticalStep = Math.abs(pos.getY() - getBlockY()) >= 1;
            double wantedY = (waypoint.move() == NpcPathfinder.Waypoint.Move.CLIMB || verticalStep)
                    ? getY()
                    : pos.getY();
            double wantedX = climbingColumn ? blockPosition().getX() + 0.5D : pos.getX() + 0.5D;
            double wantedZ = climbingColumn ? blockPosition().getZ() + 0.5D : pos.getZ() + 0.5D;
            getMoveControl().setWantedPosition(wantedX, wantedY, wantedZ,
                    waypoint.move() == NpcPathfinder.Waypoint.Move.CLIMB ? WALK_SPEED * 0.9D : WALK_SPEED);
            return;
        }
        followCompleted = true;
        followPath = List.of();
        followIndex = 0;
    }

    private boolean passedWaypoint(int index) {
        if (index + 1 >= followPath.size()) {
            return false;
        }
        NpcPathfinder.Waypoint waypoint = followPath.get(index);
        NpcPathfinder.Waypoint next = followPath.get(index + 1);
        if (waypoint.move() == NpcPathfinder.Waypoint.Move.CLIMB
                || next.move() == NpcPathfinder.Waypoint.Move.CLIMB) {
            return false;
        }
        if (isCorner(index) || waypoint.pos().distSqr(blockPosition()) > PASSED_MAX_SQR) {
            return false;
        }
        BlockPos pos = waypoint.pos();
        BlockPos nextPos = next.pos();
        if (Math.abs(nextPos.getY() - pos.getY()) > 1) {
            return false;
        }
        double dirX = nextPos.getX() - pos.getX();
        double dirZ = nextPos.getZ() - pos.getZ();
        double relX = getX() - (pos.getX() + 0.5D);
        double relZ = getZ() - (pos.getZ() + 0.5D);
        return relX * dirX + relZ * dirZ > 0.0D;
    }

    private boolean isCorner(int index) {
        if (index <= 0 || index + 1 >= followPath.size()) {
            return false;
        }
        BlockPos prev = followPath.get(index - 1).pos();
        BlockPos current = followPath.get(index).pos();
        BlockPos next = followPath.get(index + 1).pos();
        if (prev.getY() != current.getY() || current.getY() != next.getY()) {
            return true;
        }
        int ax = current.getX() - prev.getX();
        int az = current.getZ() - prev.getZ();
        int bx = next.getX() - current.getX();
        int bz = next.getZ() - current.getZ();
        return ax * bx + az * bz <= 0;
    }

    private void resyncFollowIndex() {
        if (followIndex + 1 >= followPath.size()) {
            return;
        }
        BlockPos here = blockPosition();
        double currentDist = Math.sqrt(followPath.get(followIndex).pos().distSqr(here));
        if (currentDist <= RESYNC_TRIGGER_DISTANCE) {
            return;
        }
        int best = followIndex;
        double bestDist = currentDist;
        for (int i = followIndex + 1; i < followPath.size(); i++) {
            double dist = Math.sqrt(followPath.get(i).pos().distSqr(here));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best > followIndex && bestDist + 2.0D < currentDist) {
            followIndex = best;
        }
    }

    private void autoOpenPassage(BlockPos waypoint) {
        double movedX = getX() - xo;
        double movedZ = getZ() - zo;
        boolean blocked = horizontalCollision
                && movedX * movedX + movedZ * movedZ < 1.0E-4D;
        if (!blocked && tickCount % 20 != 0) {
            return;
        }
        BlockPos base = blockPosition();
        int stepX = Integer.signum(waypoint.getX() - base.getX());
        int stepZ = Integer.signum(waypoint.getZ() - base.getZ());
        openColumn(base);
        if (stepX != 0) {
            openColumn(base.offset(stepX, 0, 0));
            openColumn(base.offset(stepX * 2, 0, 0));
        }
        if (stepZ != 0) {
            openColumn(base.offset(0, 0, stepZ));
            openColumn(base.offset(0, 0, stepZ * 2));
        }
        if (stepX != 0 && stepZ != 0) {
            openColumn(base.offset(stepX, 0, stepZ));
        }
    }

    private void openColumn(BlockPos base) {
        tryOpenDoor(base);
        tryOpenDoor(base.above());
    }

    private void tryOpenDoor(BlockPos pos) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(pos);
        if (state.is(Blocks.IRON_DOOR)) {
            return;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            if (state.getValue(FenceGateBlock.OPEN)) {
                return;
            }
            serverLevel.setBlock(pos, state.setValue(FenceGateBlock.OPEN, Boolean.TRUE),
                    Block.UPDATE_ALL);
            serverLevel.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS,
                    1.0F, 1.0F);
            return;
        }
        if (!(state.getBlock() instanceof DoorBlock)
                || state.getValue(DoorBlock.OPEN)
                || state.getValue(DoorBlock.POWERED)) {
            return;
        }
        BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState otherState = serverLevel.getBlockState(other);
        serverLevel.setBlock(pos, state.setValue(DoorBlock.OPEN, Boolean.TRUE), Block.UPDATE_ALL);
        if (otherState.getBlock() instanceof DoorBlock) {
            serverLevel.setBlock(other, otherState.setValue(DoorBlock.OPEN, Boolean.TRUE), Block.UPDATE_ALL);
        }
        serverLevel.playSound(null, pos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private double climbDelta(int index) {
        if (index < 0 || index + 1 >= followPath.size()) {
            return 0;
        }
        return followPath.get(index + 1).pos().getY() - followPath.get(index).pos().getY();
    }

    private void applyVertical(NpcPathfinder.Waypoint waypoint, double climbDirection) {
        vertical.applyVertical(waypoint, climbDirection);
    }

    private void climbMove(double climbDirection) {
        vertical.climbMove(climbDirection);
    }

    private void teleportOnLadder(BlockPos ladderCell) {
        if (vertical.onClimbable()) {
            return;
        }
        if (blockPosition().distSqr(ladderCell) > 4.0D) {
            return;
        }
        if (!isLadderCell(ladderCell)) {
            return;
        }
        teleportTo(ladderCell.getX() + 0.5D, ladderCell.getY(), ladderCell.getZ() + 0.5D);
        setDeltaMovement(Vec3.ZERO);
        vertical.resetCooldown();
        fallDistance = 0.0F;
    }

    private void teleportOffLadder(BlockPos current, BlockPos next) {
        if (isLadderCell(next)) {
            return;
        }
        if (current.distSqr(next) > 9.0D) {
            return;
        }
        teleportTo(next.getX() + 0.5D, next.getY(), next.getZ() + 0.5D);
        setDeltaMovement(Vec3.ZERO);
        vertical.resetCooldown();
        fallDistance = 0.0F;
    }

    private BlockPos adjacentStep(BlockPos current, BlockPos next) {
        int sx = Integer.signum(next.getX() - current.getX());
        int sz = Integer.signum(next.getZ() - current.getZ());
        if (sx == 0 && sz == 0) {
            int sy = Integer.signum(next.getY() - current.getY());
            return sy == 0 ? null : current.above(sy);
        }
        if (sx == 0 || sz == 0) {
            return current.offset(sx, 0, sz);
        }
        BlockPos diagonal = current.offset(sx, 0, sz);
        if (canOccupy(diagonal)) {
            return diagonal;
        }
        BlockPos alongX = current.offset(sx, 0, 0);
        if (canOccupy(alongX)) {
            return alongX;
        }
        BlockPos alongZ = current.offset(0, 0, sz);
        return canOccupy(alongZ) ? alongZ : null;
    }

    /** 只认梯子/藤蔓：脚手架不进硬传送流程 */
    private boolean isLadderCell(BlockPos pos) {
        return ChunkSnapshot.isLadderLike(level().getBlockState(pos))
                || ChunkSnapshot.isLadderLike(level().getBlockState(pos.below()));
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
        tickPathTo(target);
        setNavTarget(target);
    }

    public void haltFarm() {
        farmTarget = null;
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    public boolean isTravelling() {
        return followIndex < followPath.size() || workPathFuture != null || getNavigation().isInProgress();
    }

    public boolean tickTravelPath(BlockPos target) {
        return tickPathTo(target);
    }

    public void clearFarmTarget() {
        if (farmTarget == null) {
            return;
        }
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
        tickPathTo(target);
        setNavTarget(target);
    }

    public void haltRanch() {
        ranchTarget = null;
        navTarget = null;
        stopConstructionPath();
        pathRetry.reset();
    }

    private static boolean endpointMatches(List<NpcPathfinder.Waypoint> waypoints, BlockPos stand) {
        BlockPos reached = waypoints.get(waypoints.size() - 1).pos();
        return reached.getX() == stand.getX()
                && reached.getZ() == stand.getZ()
                && Math.abs(reached.getY() - stand.getY()) <= 1;
    }

    private List<NpcPathfinder.Waypoint> trimForLanding(List<NpcPathfinder.Waypoint> path) {
        if (rts.groupDest() == null || rts.target() == null || path.size() < 3) {
            return path;
        }
        BlockPos landing = rts.target();
        int best = path.size() - 1;
        double bestDist = Math.sqrt(path.get(best).pos().distSqr(landing));
        for (int i = best - 1; i >= 0; i--) {
            double dist = Math.sqrt(path.get(i).pos().distSqr(landing));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best >= path.size() - 1) {
            return path;
        }
        return path.subList(0, best + 1);
    }

    public void applyPath(List<NpcPathfinder.Waypoint> waypoints) {
        builderStuckTicks = 0;
        builderAnchorX = getX();
        builderAnchorY = getY();
        builderAnchorZ = getZ();
        followPath = waypoints == null ? List.of() : List.copyOf(waypoints);
        followIndex = 0;
        followCompleted = false;
        followEndPos = followPath.isEmpty() ? null : followPath.get(followPath.size() - 1).pos();
        slideTarget = null;
        slideTicks = 0;
        slideCooldown = 0;
        getNavigation().stop();
        if (followPath.isEmpty()) {
            navTarget = null;
            return;
        }
        navTarget = followEndPos;
        while (followIndex < followPath.size()
                && followPath.get(followIndex).pos().distSqr(blockPosition()) <= 0.25D) {
            followIndex++;
        }
        if (level() instanceof ServerLevel serverLevel) {
            NpcPathDebugBroadcast.send(serverLevel, this, followPath);
        }
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
    public boolean onClimbable() {
        return vertical.onClimbable();
    }

    @Override
    public boolean isDescending() {
        int nextY = followIndex < followPath.size()
                ? followPath.get(followIndex).pos().getY() : Integer.MIN_VALUE;
        return vertical.isDescending(nextY);
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (level().isClientSide()) {
            super.travel(travelVector);
            return;
        }
        if (climbInput) {
            // 模拟玩家按住跳跃：原版 handleRelativeFrictionAndCalculateMovement 会给 0.2 上升
            setJumping(climbUp);
            super.travel(travelVector);
            climbInput = false;
            return;
        }
        int stride = farTravelStride();
        if (stride > 1) {
            if (Math.floorMod(tickCount + getId(), stride) != 0) {
                return;
            }
            super.travel(travelVector.scale(stride));
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
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(DamageTypes.CRAMMING) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 受击即记仇：被谁打就立刻把战斗目标换成谁（远程被突脸必须马上掉头还手）。
        // 只在服务端做；source.getEntity() 是伤害归属者——被箭射中时是射手而不是箭。
        if (!level().isClientSide() && !isRemoved()) {
            Entity attacker = source.getEntity();
            if (attacker != null && attacker != this) {
                combat.onHurtBy(attacker);
            }
        }
        return super.hurt(source, amount);
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
                .add(Attributes.MAX_HEALTH, BASE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED)
                .add(Attributes.STEP_HEIGHT, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE);
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
        builder.define(DATA_TOURIST, false);
        builder.define(DATA_NAME, "");
        builder.define(DATA_OWNER_ID, "");
        builder.define(DATA_TERRITORY_OWNER, "");
    }

    public boolean isTouristEntity() {
        return entityData.get(DATA_TOURIST);
    }

    @Override
    public boolean canCollideWith(Entity entity) {
        if (isTouristEntity()) {
            return false;
        }
        if (entity instanceof NpcEntity other && other.isTouristEntity()) {
            return false;
        }
        return super.canCollideWith(entity);
    }

    @Override
    public boolean isPushable() {
        return !isTouristEntity();
    }

    public double getSyncedHunger() {
        return entityData.get(DATA_HUNGER);
    }

    public String getSyncedSkin() {
        return entityData.get(DATA_SKIN);
    }

    public String getSyncedName() {
        return entityData.get(DATA_NAME);
    }

    public void setSyncedName(String name) {
        entityData.set(DATA_NAME, name == null ? "" : name);
    }

    public String getSyncedOwnerId() {
        return entityData.get(DATA_OWNER_ID);
    }

    public String getSyncedTerritoryOwner() {
        return entityData.get(DATA_TERRITORY_OWNER);
    }

    public String getSyncedStatus() {
        return entityData.get(DATA_STATUS);
    }

    public boolean isArmed() {
        if (CombatWeapons.isCombatWeapon(npcInventory.getStackInSlot(NpcInventory.MAIN_HAND))) {
            return true;
        }
        if (bagWeaponPresent) {
            return true;
        }
        // 兜底：实体装备槽里已有武器（外部直接 setItemSlot / 装备同步的路径）
        return CombatWeapons.isCombatWeapon(getItemBySlot(EquipmentSlot.MAINHAND));
    }

    public ItemStack weaponStack() {
        ItemStack main = npcInventory.getStackInSlot(NpcInventory.MAIN_HAND);
        if (CombatWeapons.isCombatWeapon(main)) {
            return main;
        }
        return firstBagWeapon();
    }

    public void setCombatHold(boolean hold) {
        if (hold) {
            combat.setHoldPoint(blockPosition());
            persistCombatMode(NpcData.COMBAT_HOLD);
        } else {
            combat.clearOrders();
            persistCombatMode(NpcData.COMBAT_STANDBY);
        }
    }

    public void setCombatAttack() {
        combat.beginAttack();
        persistCombatMode(NpcData.COMBAT_ATTACK);
    }

    /**
     * 进入巡逻态：自身不索敌，只执行队伍共享目标。
     * 巡逻是队伍级常驻任务（路线存在队伍上），个体只记「当前在巡逻」这层临时态，
     * 所以入库的是「待命」，重启后由巡逻服务按队伍记录重新挂回巡逻。
     */
    public void setCombatPatrol() {
        combat.beginPatrol();
        persistCombatMode(NpcData.COMBAT_STANDBY);
    }

    /** 当前是不是巡逻态（巡逻服务用它把重载回来的队员重新挂回巡逻）。 */
    public boolean isPatrolCombatMode() {
        return combat.mode() == NpcCombatController.CombatMode.PATROL;
    }

    /** 回到「手持武器待命」：坚守/进攻/巡逻是三个并列指令，解除巡逻就是回待命。 */
    public void setCombatStandby() {
        combat.setMode(NpcCombatController.CombatMode.STANDBY);
        persistCombatMode(NpcData.COMBAT_STANDBY);
    }

    /** 玩家显式下达的战斗指令落库：重启/区块重载后靠它把状态还原回去。 */
    private void persistCombatMode(String mode) {
        if (level() == null || level().isClientSide() || citizenId == null) {
            return;
        }
        NpcData data = NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.setCombatMode(mode);
        }
    }

    public void assignPatrolTarget(Entity target) {
        combat.assignPatrolTarget(target);
    }

    public void clearPatrolTarget() {
        combat.clearPatrolTarget();
    }

    /** 是否还挂着巡逻共享目标。 */
    public boolean hasPatrolTarget() {
        return combat.hasPatrolTarget();
    }

    public boolean hasRtsOrder() {
        return rts.hasOrder();
    }

    public void cancelRtsOrder() {
        rts.release();
    }

    /** 当前 RTS 指令是不是「软指令」（队伍跟随/阵型站位），战斗可以打断它去追击。 */
    public boolean isSoftRtsOrder() {
        return rts.isSoftOrder();
    }

    public boolean combatEngaged() {
        return combat.hasTarget();
    }

    public void assignCombatTarget(Entity target) {
        combat.assignTarget(target);
    }

    public void clearCombatAggro() {
        combat.clearAggro();
    }

    public NpcDetail combatDetail() {
        return combat.detail();
    }

    public void setHeldItem(ItemStack stack) {
        ItemStack value = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.isSameItemSameComponents(entityData.get(DATA_HELD_ITEM), value)) {
            return;
        }
        entityData.set(DATA_HELD_ITEM, value);
        applyEquipment();
    }

    public void applyEquipment() {
        if (level() == null || level().isClientSide()) {
            return;
        }
        ItemStack workItem = entityData.get(DATA_HELD_ITEM);
        ItemStack main = npcInventory.getStackInSlot(NpcInventory.MAIN_HAND);
        // 武装优先：主手没有武器就从背包里取一把显示（不动背包内容），
        // 否则工作工具会把武器盖掉，看上去仍是农民。
        if (!CombatWeapons.isCombatWeapon(main)) {
            ItemStack bagWeapon = firstBagWeapon();
            if (!bagWeapon.isEmpty()) {
                main = bagWeapon;
            } else if (!workItem.isEmpty()) {
                main = workItem;
            }
        }
        setItemSlot(EquipmentSlot.MAINHAND, main);
        setItemSlot(EquipmentSlot.OFFHAND, npcInventory.getStackInSlot(NpcInventory.OFF_HAND));
        setItemSlot(EquipmentSlot.HEAD, npcInventory.getStackInSlot(NpcInventory.HEAD));
        setItemSlot(EquipmentSlot.CHEST, npcInventory.getStackInSlot(NpcInventory.CHEST));
        setItemSlot(EquipmentSlot.LEGS, npcInventory.getStackInSlot(NpcInventory.LEGS));
        setItemSlot(EquipmentSlot.FEET, npcInventory.getStackInSlot(NpcInventory.FEET));
    }

    public void playSwing() {
        entityData.set(DATA_SWING, entityData.get(DATA_SWING) + 1);
    }

    // ------------------------------------------------------------------ 盾牌

    /** 副手拿的是不是能格挡的盾（标签/原件判定走原版 ItemAbilities）。 */
    public boolean hasShield() {
        return npcInventory.getStackInSlot(NpcInventory.OFF_HAND)
                .canPerformAction(ItemAbilities.SHIELD_BLOCK);
    }

    /** 现在举着的是不是盾（用于跟弓/弩的“使用中”状态区分）。 */
    public boolean isUsingShield() {
        return isUsingItem() && getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK);
    }

    /**
     * 盾牌姿态同步：近战 + 副手有盾 → 举盾（原版右键盾的举盾姿态，客户端靠 isUsingItem 渲染），
     * 拿弓/弩或没有盾 → 放下。
     * <p>
     * 只比较几个状态位，不扫世界；调用点都在已有的战斗分支里，没有额外的每 tick 扫描。
     */
    public void syncShieldPose(boolean rangedWeapon) {
        if (level() == null || level().isClientSide()) {
            return;
        }
        boolean want = !rangedWeapon && hasShield();
        boolean holdingShield = isUsingShield();
        if (want) {
            if (holdingShield) {
                return;
            }
            // 先把弓/弩的使用状态收掉：原版 startUsingItem 在 isUsingItem() 时直接返回，抢不过来
            if (isUsingItem()) {
                stopUsingItem();
            }
            if (!isUsingItem()) {
                startUsingItem(InteractionHand.OFF_HAND);
            }
            return;
        }
        if (holdingShield) {
            stopUsingItem();
        }
    }

    public void lowerShield() {
        if (level() != null && !level().isClientSide() && isUsingShield()) {
            stopUsingItem();
        }
    }

    /**
     * 盾牌挡下伤害后的耐久损耗：原版口径，单次伤害 ≥3 才磨盾，损耗 1 + floor(伤害)。
     * 盾在背包的副手格里，磨完要回写背包（装备槽是同一个 ItemStack 引用），否则不落库。
     */
    @Override
    protected void hurtCurrentlyUsedShield(float damage) {
        if (damage < 3.0F) {
            return;
        }
        ItemStack shield = npcInventory.getStackInSlot(NpcInventory.OFF_HAND);
        if (!shield.canPerformAction(ItemAbilities.SHIELD_BLOCK) || !shield.isDamageableItem()) {
            return;
        }
        shield.hurtAndBreak(1 + Mth.floor(damage), this, EquipmentSlot.OFFHAND);
        boolean broke = shield.isEmpty();
        npcInventory.setStackInSlot(NpcInventory.OFF_HAND, broke ? ItemStack.EMPTY : shield);
        if (broke && isUsingItem()) {
            // 盾碎了就别继续摆着举盾姿势
            stopUsingItem();
        }
        applyEquipment();
    }

    /**
     * 力量 → 属性：最大生命 = 20 + 20 × 力量/100，基础攻击伤害 = 2 + 2 × 力量/100。
     * <p>
     * 这里只写「属性」这一层，具体一刀打多少还要再叠手持武器/附魔（后续做）；
     * 所有数值只保留 1 位小数。
     * <p>
     * 调用时机是**事件驱动**：市民数据到位（进世界/设 citizenId）和力量值真的变了才调，
     * 不在 tick 里逐帧刷。
     */
    public void syncTalent(NpcData data) {
        if (data == null || level() == null || level().isClientSide()) {
            return;
        }
        int strength = data.getStrength();
        if (entityData.get(DATA_STRENGTH) != strength) {
            entityData.set(DATA_STRENGTH, strength);
        }
        if (entityData.get(DATA_INTELLIGENCE) != data.getIntelligence()) {
            entityData.set(DATA_INTELLIGENCE, data.getIntelligence());
        }
        if (entityData.get(DATA_AGILITY) != data.getAgility()) {
            entityData.set(DATA_AGILITY, data.getAgility());
        }
        applyStrength(strength);
    }

    /** 力量 → 最大生命 / 基础攻击伤害（只算属性，不含武器；数值保留 1 位小数）。 */
    private void applyStrength(int strength) {
        double maxHealth = roundTalent(BASE_MAX_HEALTH + BASE_MAX_HEALTH * strength / STRENGTH_PERCENT);
        double attackDamage = roundTalent(BASE_ATTACK_DAMAGE
                + BASE_ATTACK_DAMAGE * strength / STRENGTH_PERCENT);
        applyMaxHealth(maxHealth);
        AttributeInstance attack = getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && Math.abs(attack.getBaseValue() - attackDamage) > 1.0E-4D) {
            attack.setBaseValue(attackDamage);
        }
    }

    /**
     * 力量会改血量上限。只做两件事：
     * 原本满血的（刚生成/刚加载且没受过伤）跟着补满；上限变小而当前血量超了才夹住。
     * 带着伤重载世界不会被“免费治疗”。
     */
    private void applyMaxHealth(double maxHealth) {
        AttributeInstance attribute = getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null || Math.abs(attribute.getBaseValue() - maxHealth) <= 1.0E-4D) {
            return;
        }
        boolean wasFull = getHealth() >= attribute.getBaseValue() - 1.0E-3D;
        attribute.setBaseValue(maxHealth);
        if (wasFull) {
            setHealth((float) maxHealth);
        } else if (getHealth() > maxHealth) {
            setHealth((float) maxHealth);
        }
    }

    private static double roundTalent(double value) {
        return Math.round(value * 10.0D) / 10.0D;
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
            swingAnimStart = tickCount;
            swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 客户端挥击动画的起始 tick；模型用 (当前年龄 - 这个值) 算摆动进度。 */
    public int swingAnimStart() {
        return swingAnimStart;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (citizenId != null) {
            tag.putUUID(KEY_CITIZEN_ID, citizenId);
            NpcData data = NpcManager.INSTANCE.getNpc(citizenId);
            if (data != null) {
                tag.putInt(KEY_STRENGTH, data.getStrength());
            }
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_CITIZEN_ID)) {
            citizenId = tag.getUUID(KEY_CITIZEN_ID);
        }
        // 必须在读完血量之后、任何 clamp 之前把上限还原，否则带伤重载会被按 20 血上限截断
        if (tag.contains(KEY_STRENGTH)) {
            applyStrength(tag.getInt(KEY_STRENGTH));
        }
        loadInventory();
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
        loadInventory();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            // 事件驱动：市民数据一挂上就把力量属性落一次，不靠 tick 刷
            syncTalent(data);
        }
    }

    public NpcInventory getNpcInventory() {
        return npcInventory;
    }

    private NpcInventory createInventory() {
        NpcInventory inventory = new NpcInventory();
        inventory.setChangeListener(changed -> {
            refreshArmedCache();
            saveInventory();
        });
        return inventory;
    }

    /**
     * 背包里有没有战斗武器（任意格）。
     * 玩家给市民发武器基本都塞背包格，只认主手槽会让"给了剑还是农民"；
     * 这里缓存成布尔，避免 brain 每 tick 对每个市民扫 24 格。
     */
    private boolean bagWeaponPresent;

    private void refreshArmedCache() {
        bagWeaponPresent = false;
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            if (CombatWeapons.isCombatWeapon(npcInventory.getStackInSlot(slot))) {
                bagWeaponPresent = true;
                return;
            }
        }
    }

    /** 背包里第一把战斗武器，没有则空。 */
    private ItemStack firstBagWeapon() {
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = npcInventory.getStackInSlot(slot);
            if (CombatWeapons.isCombatWeapon(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
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
        applyEquipment();
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
        // 反序列化直接写内部数组，不会触发 onContentsChanged，武装缓存要手动同步
        refreshArmedCache();
        applyEquipment();
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        if (level().isClientSide()) {
            return;
        }
        for (int slot = 0; slot < npcInventory.getSlots(); slot++) {
            ItemStack stack = npcInventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack);
                npcInventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot equipmentSlot : EquipmentSlot.values()) {
            setItemSlot(equipmentSlot, ItemStack.EMPTY);
        }
        refreshArmedCache();
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (!level().isClientSide()) {
            // 所有按实体 UUID 索引的运行时缓存统一在 purgeCitizenData 里收口
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
        // 先摘 live 索引（它按 citizenId 存），再清空 citizenId
        NpcManager.INSTANCE.unregisterLive(this);
        citizenId = null;
        // 市民 id 与实体 UUID 不是同一个：前者是数据层主键，后者的各类运行时缓存必须在这里清
        UUID entityId = getUUID();
        clearSocialPartner();
        if (isSitting()) {
            setSitting(false);
        }
        NpcManager.INSTANCE.removeNpc(id);
        RegionTaskService.INSTANCE.onCitizenRemoved(id);
        if (level() instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            com.xy2407.kingdom.server.rts.RtsTeamManager.removeMemberOnDeath(serverLevel, entityId);
            com.xy2407.kingdom.server.rts.RtsOpsService.clearHolding(entityId);
            RtsSelectionService.onEntityRemoved(entityId);
            NpcLumberService.onNpcRemoved(entityId);
            notifyClientRemoval(serverLevel, entityId);
            BuilderBoardService.broadcastAll(serverLevel.getServer());
        }
    }

    /** 通知所有客户端把这个市民从编组/红框/选中里摘掉（纯客户端缓存，服务端替不了）。 */
    private static void notifyClientRemoval(ServerLevel level, UUID entityId) {
        if (entityId == null || level.getServer() == null) {
            return;
        }
        NpcRemovalPacket packet = new NpcRemovalPacket(entityId);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    public boolean isSitting() {
        return entityData.get(DATA_SITTING);
    }

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
        if (data.getEntityUuid() == null) {
            data.setEntityUuid(entity.getUUID());
        }
        data.setDimension(entity.level().dimension().location().toString());
        data.setChunk(entity.chunkPosition().toLong());
        NpcManager.INSTANCE.registerLive(entity);
        entity.syncTalent(data);
        entity.restoreCombatOrder(data);
    }

    /**
     * 还原玩家上次下达的战斗指令（坚守/进攻）。
     * 只还原 STANDBY 之外的显式命令；当前会话已有活动指令时不覆盖，避免重复加入世界把状态打回原型。
     */
    private void restoreCombatOrder(NpcData data) {
        if (combat.mode() != NpcCombatController.CombatMode.STANDBY) {
            return;
        }
        String mode = data.getCombatMode();
        if (NpcData.COMBAT_HOLD.equals(mode)) {
            // 坚守点就是存档时的脚下位置：重载后就地钉住，等玩家再下新指令
            combat.setHoldPoint(blockPosition());
            RtsOpsService.markHolding(getUUID());
        } else if (NpcData.COMBAT_ATTACK.equals(mode)) {
            combat.beginAttack();
        }
    }

    public static void onLeaveWorld(NpcEntity entity) {
        NpcData data = entity.citizenId == null ? null : NpcManager.INSTANCE.getNpc(entity.citizenId);
        if (data == null) {
            return;
        }
        if (entity.level() != null && !entity.level().isClientSide()) {
            data.setDimension(entity.level().dimension().location().toString());
            data.setChunk(entity.chunkPosition().toLong());
            KingdomPersistence.submit(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID, data.getId().toString(), data.toValues());
            data.clearDirty();
        }
        NpcManager.INSTANCE.unregisterLive(entity);
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
        if (!combatOrderRestored) {
            // 市民数据是懒加载的：加入世界那一拍可能还没读到库，这里补一次还原（只会成功一次）
            combatOrderRestored = true;
            restoreCombatOrder(data);
        }
        float hunger = (float) data.getHunger();
        if (entityData.get(DATA_HUNGER) != hunger) {
            entityData.set(DATA_HUNGER, hunger);
        }
        String skin = data.getSkin();
        if (!entityData.get(DATA_SKIN).equals(skin)) {
            entityData.set(DATA_SKIN, skin);
        }
        String status = data.getDetail().label();
        if (!entityData.get(DATA_STATUS).equals(status)) {
            entityData.set(DATA_STATUS, status);
        }
        String citizenName = data.getName();
        if (citizenName != null && !citizenName.equals(entityData.get(DATA_NAME))) {
            entityData.set(DATA_NAME, citizenName);
        }
        String ownerId = data.getOwnerId();
        if (!ownerId.equals(entityData.get(DATA_OWNER_ID))) {
            entityData.set(DATA_OWNER_ID, ownerId);
        }
        String territoryOwner = "";
        if (!data.getTerritoryId().isBlank()) {
            var territory = com.xy2407.kingdom.server.faction.TerritoryRegistry.INSTANCE
                    .byId(data.getTerritoryId());
            if (territory != null && territory.owner() != null) {
                territoryOwner = territory.owner().toString();
            }
        }
        if (!territoryOwner.equals(entityData.get(DATA_TERRITORY_OWNER))) {
            entityData.set(DATA_TERRITORY_OWNER, territoryOwner);
        }
        if (entityData.get(DATA_TOURIST) != data.isTourist()) {
            entityData.set(DATA_TOURIST, data.isTourist());
        }
        if (data.getEntityUuid() == null) {
            data.setEntityUuid(getUUID());
        }
        // 力量/天赋属性不在这里刷：进世界、设 citizenId、力量值变更时事件驱动同步
        NpcBrain brain = data.getBrain();
        if (testFollowing) {
            tickTestFollow();
            return;
        }
        if (brain.getState() == NpcState.COMBAT) {
            rts.tick();
            combat.tick();
            return;
        }
        if (brain.getState() == NpcState.COMMANDED) {
            rts.tick();
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
            case MINING -> {
                stopSitting();
                NpcMiningService.tick(this, data);
            }
            case LOGGING -> {
                stopSitting();
                com.xy2407.kingdom.server.lumber.NpcLumberService.tick(this);
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
            case SELLING -> {
                stopSitting();
                NpcShopService.tick(this, data);
            }
            case DELIVERING -> {
                stopSitting();
                NpcFreightService.tick(this, data);
            }
            case RANCHING -> stopSitting();
            case STORING_PRODUCE -> {
                stopSitting();
                if (brain.isMiningControlled()) {
                    NpcMiningService.tick(this, data);
                } else if (brain.isFarmControlled()) {
                    NpcFarmService.tick(this, data);
                } else if (brain.isLumberControlled()) {
                    com.xy2407.kingdom.server.lumber.NpcLumberService.tick(this);
                }
            }
            case TOURIST -> com.xy2407.kingdom.server.tourism.TourismService.INSTANCE
                    .tickTourist(this, data);
            case GOING_WORK -> {
                tickPathTo(new BlockPos(data.getWorkX(), data.getWorkY(), data.getWorkZ()));
            }
            case GOING_HOME -> {
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
        if (detail == NpcDetail.WANDERING || detail == NpcDetail.SLACKING) {
            pathRetry.tick(this);
        }
    }

    private void onDetailEnter() {
        idle.reset();
        forageTarget = null;
        if (seatPos != null && activeDetail != NpcDetail.DINING) {
            standUpFromSeat();
        }
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
        if (com.xy2407.kingdom.server.rts.RtsSelectionService.isSelectedNpc(getUUID())
                || com.xy2407.kingdom.server.rts.RtsTeamManager.isEditFrozen(getUUID())) {
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        idle.tick(brain);
    }

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
        idle.wanderAround(x, y, z);
    }

    private void sleepAtHome(NpcData data) {
        if (data.getHomeId() == null) {
            getNavigation().stop();
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos bed = HomeBeds.assignedBed(serverLevel, data);
        if (bed == null) {
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
        tickPathTo(stand);
    }

    boolean nearTarget(BlockPos target, double arriveSqr) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= arriveSqr
                && Math.abs(Math.floor(getY() + 0.5D) - target.getY()) < 1.0D;
    }

    public void clearNavTarget() {
        navTarget = null;
        pathRetry.reset();
    }

    public boolean walkToRandomPoint(double radius) {
        return idle.walkToRandomPoint(blockPosition(), radius);
    }

    public boolean walkToRandomPoint(BlockPos center, double radius) {
        return idle.walkToRandomPoint(center, radius);
    }

    private void clearSocialPartner() {
        idle.clearSocialPartner();
    }

    public void sitAt(BlockPos seat) {
        seatPos = seat.immutable();
        BlockState state = level().getBlockState(seat);
        Direction facing = state.hasProperty(WoodenChairBlock.FACING)
                ? state.getValue(WoodenChairBlock.FACING) : Direction.SOUTH;
        setPos(seat.getX() + 0.5D, seat.getY() + 0.375D, seat.getZ() + 0.5D);
        setDeltaMovement(Vec3.ZERO);
        setYRot(facing.toYRot());
        setYHeadRot(facing.toYRot());
        setSitting(true);
        noPhysics = true;
        getNavigation().stop();
        clearNavTarget();
    }

    public void standUpFromSeat() {
        BlockPos seat = seatPos;
        seatPos = null;
        noPhysics = false;
        if (isSitting()) {
            setSitting(false);
        }
        if (seat != null) {
            setPos(seat.getX() + 0.5D, seat.getY() + 1.0D, seat.getZ() + 0.5D);
            setDeltaMovement(Vec3.ZERO);
        }
    }

    void stopSitting() {
        if (seatPos != null) {
            standUpFromSeat();
            return;
        }
        idle.stopSitting();
    }

    NpcIdleBehavior idle() {
        return idle;
    }

    net.minecraft.util.RandomSource randomSource() {
        return random;
    }
}
