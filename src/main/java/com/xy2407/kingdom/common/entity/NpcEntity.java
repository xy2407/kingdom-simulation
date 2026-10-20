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
import com.xy2407.kingdom.common.path.*;
import com.xy2407.kingdom.server.path.RoadRouteService;
import com.xy2407.kingdom.server.task.BuilderBoardService;
import com.xy2407.kingdom.server.task.RegionTaskService;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.damagesource.DamageTypes;
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

    private final NpcInventory npcInventory = createInventory();
    private boolean inventoryLoading;
    private static final double WALK_SPEED = 0.5D;
    private static final double ARRIVE_SQR = 1.0D;
    private static final double WORK_ARRIVE_SQR = 1.0D;
    private static final double HOME_ARRIVE_SQR = 1.0D;
    private static final int WORK_STUCK_TICKS = 40;
    private static final int WORK_STUCK_LIMIT = 3;
    private static final int WORK_SEARCH_FAIL_LIMIT = 8;
    private static final int LOCAL_RECONNECT_COOLDOWN_TICKS = 60;
    private static final int LOCAL_RECONNECT_FAIL_TICKS = 100;
    private static final long WORK_PATH_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int BUILDER_STUCK_TELEPORT_TICKS = 40;
    private static final double[] SLIDE_OFFSETS = {0.25D, 0.5D, 0.75D, 1.0D};
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
    private final NpcPathRetry pathRetry = new NpcPathRetry();
    private final NpcRtsController rts = new NpcRtsController(this);
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
    private BlockPos workStandSource;
    private BlockPos workStand;
    private BlockPos segmentTarget;
    private BlockPos workAnchor;
    private int workStuckTicks;
    private int workFailures;
    private Vec3 slideTarget;
    private int slideTicks;
    private int slideCooldown;
    private int localReconnectCooldown;
    private int pathPaddingStage;
    private int builderStuckTicks;
    private double builderStuckBestDist = Double.MAX_VALUE;


    public void setRtsTarget(BlockPos target, boolean clearOnArrive) {
        rts.setTarget(target, clearOnArrive);
    }

    public void setHighRtsTarget(BlockPos target, boolean clearOnArrive) {
        setRtsTarget(target, clearOnArrive);
    }

    public void setRtsGroupTarget(BlockPos landing, BlockPos groupTarget, boolean clearOnArrive) {
        rts.setGroupTarget(landing, groupTarget, clearOnArrive);
    }

    public void setRtsWaypoints(List<BlockPos> waypoints) {
        rts.setWaypoints(waypoints);
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
        boolean closeEnough = nearTarget(target, 1.0D) && canOccupy(blockPosition());
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
                && Math.abs(Math.floor(getY() + 0.5D) - stand.getY()) < 1.0D
                && canOccupy(blockPosition())) {
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
        if (rts.target() != null) {
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
        // 三段式是同步主线程开销，只服务日常通勤；RTS 指令走共享路径/异步 A*，否则千级 NPC 会卡死主线程
        if (future == null && preferRoad) {
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
            session.preferRoad(preferRoad);
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
        vertical.resetCooldown();
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

    boolean canOccupy(BlockPos pos) {
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
        vertical.applyVertical(waypoint, climbDirection);
    }

    private void climbMove(double climbDirection) {
        vertical.climbMove(climbDirection);
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
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED)
                .add(Attributes.STEP_HEIGHT, 1.0D);
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
        if (entityData.get(DATA_STRENGTH) != data.getStrength()) {
            entityData.set(DATA_STRENGTH, data.getStrength());
        }
        if (entityData.get(DATA_INTELLIGENCE) != data.getIntelligence()) {
            entityData.set(DATA_INTELLIGENCE, data.getIntelligence());
        }
        if (entityData.get(DATA_AGILITY) != data.getAgility()) {
            entityData.set(DATA_AGILITY, data.getAgility());
        }
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
        RegionTaskService.INSTANCE.onCitizenRemoved(id);
        if (level() instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            BuilderBoardService.broadcastAll(serverLevel.getServer());
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
        if (data.getEntityUuid() == null) {
            data.setEntityUuid(getUUID());
        }
        data.setDimension(dimensionId());
        data.setPosition(getX(), getY(), getZ());
        syncTalent(data);
        NpcBrain brain = data.getBrain();
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
        idle.reset();
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

    void clearNavTarget() {
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

    void stopSitting() {
        idle.stopSitting();
    }

    NpcIdleBehavior idle() {
        return idle;
    }

    net.minecraft.util.RandomSource randomSource() {
        return random;
    }
}
