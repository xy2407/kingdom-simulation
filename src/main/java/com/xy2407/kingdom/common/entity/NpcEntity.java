package com.xy2407.kingdom.common.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugBroadcast;
import com.xy2407.kingdom.common.npc.*;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.common.path.NpcPathRetry;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.RtsSharedPath;
import com.xy2407.kingdom.server.path.RoadRouteService;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.minecraft.core.BlockPos;
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
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    private static final double RTS_DENSE_ARRIVE_SQR = 0.5625D;
    private static final double RTS_CROWD_ARRIVE_SQR = 2.25D;
    private static final double RTS_GROUP_HANDOFF_SQR = 9.0D;
    private static final double RTS_ARRIVE_HEIGHT = 3.0D;
    private static final int RTS_GIVE_UP_TICKS = 600;
    private static final int WORK_STUCK_TICKS = 40;
    private static final int WORK_STUCK_LIMIT = 3;
    private static final int WORK_SEARCH_FAIL_LIMIT = 8;
    private static final long WORK_PATH_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int BUILDER_STUCK_TELEPORT_TICKS = 40;
    private static final int WORK_CLIMB_INTERVAL_TICKS = 10;
    private static final double[] SLIDE_OFFSETS = {0.25D, 0.5D};
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
    private boolean sitWalking;
    private boolean socialized;
    private NpcDetail activeDetail;
    private NpcBrain.WanderStyle activeStyle;
    private NpcEntity socialPartner;
    private ResourceKey<Level> cachedDimension;
    private String cachedDimensionId;
    private Vec3 lastTravelPos;
    private int stillTicks;
    private int climbStateTick = -1;
    private boolean climbState;
    private BlockPos forageTarget;
    private BlockPos farmTarget;
    private BlockPos ranchTarget;
    private BlockPos navTarget;
    private final NpcPathRetry pathRetry = new NpcPathRetry();
    private BlockPos rtsTarget;
    private boolean rtsClearOnArrive;
    private double rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
    private BlockPos rtsGroupDest;
    private int rtsStuckTicks;
    private BlockPos rtsStuckAnchor;
    private final ArrayDeque<BlockPos> rtsWaypoints = new ArrayDeque<>();
    private Future<NpcPathfinder.PathResult> workPathFuture;
    private boolean workPathJoined;
    private NpcPathfinder.Session workSession;
    private List<NpcPathfinder.Waypoint> followPath = List.of();
    private int followIndex;
    private boolean followCompleted;
    private BlockPos followEndPos;
    private long sharedPathUsedKey = Long.MIN_VALUE;
    private int workRepathCooldown;
    private long workPathDeadlineNanos;
    private int workClimbCooldown;
    private BlockPos workStandSource;
    private BlockPos workStand;
    private BlockPos segmentTarget;
    private BlockPos workAnchor;
    private int workStuckTicks;
    private int workFailures;
    private Vec3 slideTarget;
    private int slideTicks;
    private int slideCooldown;
    private int pathPaddingStage;
    private int builderStuckTicks;
    private double builderStuckBestDist = Double.MAX_VALUE;


    public void setRtsTarget(BlockPos target, boolean clearOnArrive) {
        rtsWaypoints.clear();
        rtsGroupDest = null;
        rtsStuckTicks = 0;
        rtsStuckAnchor = null;
        sharedPathUsedKey = Long.MIN_VALUE;
        applyRtsTarget(target, clearOnArrive);
    }

    public void setHighRtsTarget(BlockPos target, boolean clearOnArrive) {
        setRtsTarget(target, clearOnArrive);
    }

    public void setRtsGroupTarget(BlockPos landing, BlockPos groupTarget, boolean clearOnArrive) {
        rtsWaypoints.clear();
        rtsGroupDest = groupTarget;
        rtsStuckTicks = 0;
        rtsStuckAnchor = null;
        sharedPathUsedKey = Long.MIN_VALUE;
        applyRtsTarget(landing, clearOnArrive);
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
        rtsGroupDest = null;
        rtsStuckTicks = 0;
        rtsStuckAnchor = null;
        sharedPathUsedKey = Long.MIN_VALUE;
        stopConstructionPath();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
        }
        setNavTarget(rtsTarget);
    }

    public List<BlockPos> getRtsWaypoints() {
        return new ArrayList<>(rtsWaypoints);
    }

    public void clearRtsWaypoints() {
        rtsWaypoints.clear();
    }

    private void releaseRtsControl() {
        rtsTarget = null;
        rtsWaypoints.clear();
        rtsGroupDest = null;
        sharedPathUsedKey = Long.MIN_VALUE;
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(false);
        }
    }

    public void clearMoveTargets() {
        forageTarget = null;
        farmTarget = null;
        ranchTarget = null;
        rtsTarget = null;
        rtsGroupDest = null;
        sharedPathUsedKey = Long.MIN_VALUE;
        rtsWaypoints.clear();
        clearNavTarget();
    }

    private void applyRtsTarget(BlockPos target, boolean clearOnArrive) {
        rtsTarget = target;
        rtsClearOnArrive = clearOnArrive;
        rtsArriveSqr = RTS_DENSE_ARRIVE_SQR;
        stopConstructionPath();
        NpcData data = citizenId == null ? null : NpcManager.INSTANCE.getNpc(citizenId);
        if (data != null) {
            data.getBrain().setRtsControlled(true);
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
            rtsWaypoints.clear();
            if (rtsClearOnArrive) {
                discard();
                return;
            }
            stopSitting();
            getNavigation().stop();
            releaseRtsControl();
            return;
        }
        stopSitting();
        BlockPos now = blockPosition();
        if (rtsStuckAnchor == null || !rtsStuckAnchor.equals(now)) {
            rtsStuckAnchor = now;
            rtsStuckTicks = 0;
        } else if (++rtsStuckTicks >= RTS_GIVE_UP_TICKS) {
            getNavigation().stop();
            releaseRtsControl();
            return;
        }
        advanceRtsWaypoints();
        BlockPos segment = rtsWaypoints.isEmpty() ? target : rtsWaypoints.peekFirst();
        tickHighPath(segment);
    }

    private void tickHighPath(BlockPos target) {
        if (tickPathTo(target)) {
            getNavigation().stop();
        }
    }

    private void advanceRtsWaypoints() {
        BlockPos node = rtsWaypoints.peekFirst();
        if (node == null) {
            return;
        }
        double dx = getX() - (node.getX() + 0.5D);
        double dz = getZ() - (node.getZ() + 0.5D);
        if (dx * dx + dz * dz < RTS_CROWD_ARRIVE_SQR
                && Math.abs(getY() - node.getY()) < RTS_ARRIVE_HEIGHT) {
            rtsWaypoints.poll();
            sharedPathUsedKey = Long.MIN_VALUE;
            stopConstructionPath();
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
        } catch (CancellationException e) {
            return null;
        }
    }

    private boolean isRtsArrived(BlockPos target) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        double limit = rtsClearOnArrive ? RTS_CROWD_ARRIVE_SQR : rtsArriveSqr;
        return dx * dx + dz * dz < limit && Math.abs(getY() - target.getY()) < RTS_ARRIVE_HEIGHT;
    }

    public boolean tickConstructionPath(BlockPos target) {
        double adx = getX() - (target.getX() + 0.5D);
        double adz = getZ() - (target.getZ() + 0.5D);
        boolean closeEnough = adx * adx + adz * adz <= 4.0D
                && Math.abs(Math.floor(getY() + 0.5D) - target.getY()) <= 2.0D;
        boolean arrived = closeEnough || tickPathTo(target);
        if (arrived) {
            builderStuckTicks = 0;
            builderStuckBestDist = Double.MAX_VALUE;
        } else {
            checkBuilderStuck(target);
        }
        if (closeEnough) {
            followPath = List.of();
            followIndex = 0;
        }
        return arrived;
    }

    private boolean tickPathTo(BlockPos target) {
        stopSitting();
        BlockPos effective = rtsGroupDest == null ? target : rtsGroupDest;
        BlockPos stand = resolveWorkStand(effective);
        stand = applySegment(stand);
        double dx = getX() - (stand.getX() + 0.5D);
        double dz = getZ() - (stand.getZ() + 0.5D);
        double arriveSqr = rtsTarget == null ? 1.0D : (rtsClearOnArrive ? RTS_CROWD_ARRIVE_SQR : rtsArriveSqr);
        if (dx * dx + dz * dz < arriveSqr && Math.abs(Math.floor(getY() + 0.5D) - stand.getY()) < 1.0D) {
            stopConstructionPath();
            if (rtsGroupDest != null && blockPosition().distSqr(rtsGroupDest) < RTS_GROUP_HANDOFF_SQR) {
                rtsGroupDest = null;
            }
            return true;
        }
        if (followIndex < followPath.size()) {
            followWaypoints();
            if (trackWorkStuck()) {
                if (trySlide()) {
                    workStuckTicks = 0;
                } else {
                    escalateWork(stand, WORK_STUCK_LIMIT);
                }
            }
            return false;
        }
        if (rtsGroupDest != null && followCompleted) {
            double gdx = getX() - (rtsGroupDest.getX() + 0.5D);
            double gdz = getZ() - (rtsGroupDest.getZ() + 0.5D);
            boolean atAnchor = followEndPos != null
                    && followEndPos.getX() == stand.getX()
                    && followEndPos.getZ() == stand.getZ()
                    && Math.abs(followEndPos.getY() - stand.getY()) <= 1;
            if (gdx * gdx + gdz * gdz < RTS_GROUP_HANDOFF_SQR || !atAnchor) {
                rtsGroupDest = null;
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
                    workRepathCooldown = 20;
                    workFailures = 0;
                    pathPaddingStage = 0;
                    applyPath(trimmed);
                } else if (result.missingChunks() > 0) {
                    workRepathCooldown = 10;
                } else {
                    pathPaddingStage++;
                    workRepathCooldown = 10;
                    escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
                }
            } else if (result != null && result.missingChunks() > 0) {
                workRepathCooldown = 10;
            } else {
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
        BlockPos from = blockPosition();
        Future<NpcPathfinder.PathResult> future = null;
        if (rtsTarget != null) {
            RtsSharedPath.Join join = RtsSharedPath.get(level.getServer())
                    .joinOrSubmit(level, from, stand, NpcPathfinder.paddingOf(pathPaddingStage), getId());
            if (join != null) {
                if (join.readyPath() != null) {
                    List<NpcPathfinder.Waypoint> trimmed = trimForLanding(join.readyPath());
                    if (trimmed.size() < join.readyPath().size()) {
                        sharedPathUsedKey = stand.asLong();
                    }
                    workRepathCooldown = 20;
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
        if (future == null) {
            // 道路路由（P3）：目的地是建筑且两端接入同一连通分量时，三段式同步毫秒级出路径；
            // 任何一段不成则返回 null 回退原异步快照寻路
            List<NpcPathfinder.Waypoint> roadRoute = RoadRouteService.tryPlan(level, from, stand);
            if (roadRoute != null) {
                workRepathCooldown = 20;
                workFailures = 0;
                pathPaddingStage = 0;
                workSession = null;
                applyPath(roadRoute);
                return false;
            }
            SnapshotRepository repository = SnapshotRepository.get(level.getServer());
            if (repository == null) {
                return false;
            }
            NpcPathfinder.Session session = workSession;
            if (session == null || !session.matches(from, stand)) {
                session = new NpcPathfinder.Session(from, stand);
                workSession = session;
            }
            future = repository.submitSessionSearch(level, session, from, stand,
                    NpcPathfinder.paddingOf(pathPaddingStage));
            workPathJoined = false;
        }
        if (future == null) {
            workRepathCooldown = 10;
            escalateWork(stand, WORK_SEARCH_FAIL_LIMIT);
            return false;
        }
        workPathFuture = future;
        workPathDeadlineNanos = System.nanoTime() + WORK_PATH_TIMEOUT_NANOS;
        return false;
    }

    private void checkBuilderStuck(BlockPos target) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        double dy = Math.abs(Math.floor(getY() + 0.5D) - target.getY());
        double dist = dx * dx + dz * dz + dy * dy;
        if (dist < builderStuckBestDist - 0.01D) {
            builderStuckBestDist = dist;
            builderStuckTicks = 0;
            return;
        }
        if (++builderStuckTicks < BUILDER_STUCK_TELEPORT_TICKS) {
            return;
        }
        builderStuckTicks = 0;
        builderStuckBestDist = Double.MAX_VALUE;
        stopConstructionPath();
        setDeltaMovement(Vec3.ZERO);
        teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
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
        workClimbCooldown = 0;
        workStandSource = null;
        workStand = null;
        workAnchor = null;
        workStuckTicks = 0;
        workFailures = 0;
        pathPaddingStage = 0;
        workSession = null;
        builderStuckTicks = 0;
        builderStuckBestDist = Double.MAX_VALUE;
        slideTarget = null;
        slideTicks = 0;
        slideCooldown = 0;
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

    private boolean canOccupy(BlockPos pos) {
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
        pathPaddingStage = 0;
        setDeltaMovement(Vec3.ZERO);
    }

    private BlockPos applySegment(BlockPos stand) {
        if (segmentTarget != null && nearTarget(segmentTarget, WORK_ARRIVE_SQR)) {
            segmentTarget = null;
        }
        if (segmentTarget != null) {
            return segmentTarget;
        }
        if (blockPosition().distSqr(stand) <= (double) NpcPathfinder.SEGMENT_RANGE
                * NpcPathfinder.SEGMENT_RANGE) {
            return stand;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return stand;
        }
        SnapshotRepository repository = SnapshotRepository.get(serverLevel.getServer());
        BlockPos midway = NpcPathfinder.midway(repository, serverLevel.dimension(),
                blockPosition(), stand, NpcPathfinder.paddingOf(pathPaddingStage));
        if (midway == null) {
            return stand;
        }
        segmentTarget = new BlockPos((midway.getX() >> 3) << 3, midway.getY(), (midway.getZ() >> 3) << 3);
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

    private void followWaypoints() {
        if (workClimbCooldown > 0) {
            workClimbCooldown--;
        }
        if (slideCooldown > 0) {
            slideCooldown--;
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
        while (followIndex < followPath.size()) {
            NpcPathfinder.Waypoint waypoint = followPath.get(followIndex);
            BlockPos pos = waypoint.pos();
            if (passedWaypoint(followIndex)) {
                followIndex++;
                continue;
            }
            double dx = getX() - (pos.getX() + 0.5D);
            double dz = getZ() - (pos.getZ() + 0.5D);
            double arrive = switch (waypoint.move()) {
                case CLIMB -> 1.15D;
                case WALK -> 0.5D;
                default -> 1.0D;
            };
            if (dx * dx + dz * dz <= arrive * arrive
                    && Math.abs(Math.floor(getY() + 0.5D) - pos.getY()) < 1.0D) {
                followIndex++;
                continue;
            }
            applyVertical(waypoint, climbDelta(followIndex));
            tryOpenDoor(pos);
            double wantedY = waypoint.move() == NpcPathfinder.Waypoint.Move.CLIMB
                    ? getY()
                    : pos.getY();
            getMoveControl().setWantedPosition(pos.getX() + 0.5D, wantedY, pos.getZ() + 0.5D,
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

    private void tryOpenDoor(BlockPos pos) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(pos);
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
        int dy = waypoint.pos().getY() - getBlockY();
        if (onClimbable() && Math.abs(dy) >= 1) {
            climbMove(dy > 0 ? 1.0D : -1.0D);
            return;
        }
        switch (waypoint.move()) {
            case STEP_UP -> {
                if (onGround() && workClimbCooldown <= 0 && getDeltaMovement().y <= 0.02D) {
                    workClimbCooldown = WORK_CLIMB_INTERVAL_TICKS;
                    getJumpControl().jump();
                }
            }
            case CLIMB -> climbMove(climbDirection);
            default -> {
            }
        }
    }

    private void climbMove(double climbDirection) {
        Vec3 movement = getDeltaMovement();
        if (climbDirection > 0) {
            setDeltaMovement(movement.x * 0.5D, 0.2D, movement.z * 0.5D);
        } else if (climbDirection < 0) {
            setDeltaMovement(movement.x * 0.5D, -0.2D, movement.z * 0.5D);
        }
        fallDistance = 0.0F;
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
        if (rtsGroupDest == null || rtsTarget == null || path.size() < 3) {
            return path;
        }
        BlockPos landing = rtsTarget;
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
        int now = tickCount;
        if (climbStateTick == now) {
            return climbState;
        }
        climbStateTick = now;
        climbState = level().getBlockState(blockPosition()).is(BlockTags.CLIMBABLE)
                || level().getBlockState(blockPosition().below()).is(BlockTags.CLIMBABLE);
        return climbState;
    }

    @Override
    public boolean isDescending() {
        BlockPos pos = blockPosition();
        boolean onClimbable = level().getBlockState(pos).is(BlockTags.CLIMBABLE)
                || level().getBlockState(pos.below()).is(BlockTags.CLIMBABLE);
        if (!onClimbable) {
            return false;
        }
        if (followIndex < followPath.size()) {
            return followPath.get(followIndex).pos().getY() < getBlockY();
        }
        return false;
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
        sitWalking = false;
        socialized = false;
        activeStyle = null;
        clearSocialPartner();
        forageTarget = null;
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

    private boolean nearTarget(BlockPos target, double arriveSqr) {
        double dx = getX() - (target.getX() + 0.5D);
        double dz = getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= arriveSqr
                && Math.abs(Math.floor(getY() + 0.5D) - target.getY()) < 1.0D;
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
