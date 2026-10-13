package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Future;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * RTS 群组共享路径缓存：同目的地共用一次 A*，完成后生成多车道副本供成员直接取用。
 */
public final class RtsSharedPath {

    public record Join(Future<NpcPathfinder.PathResult> future,
                       List<NpcPathfinder.Waypoint> readyPath, boolean shared) {
    }

    private record Entry(Future<NpcPathfinder.PathResult> future, BlockPos origin, long createdAt,
                         List<List<NpcPathfinder.Waypoint>> lanes) {
    }

    private static final double JOIN_RADIUS_SQR = 24.0D * 24.0D;
    private static final long ENTRY_TTL_TICKS = 600L;
    private static final double[] LANE_OFFSETS = {0.0D, 1.0D, -1.0D, 2.0D, -2.0D, 3.0D, -3.0D};
    private static final int LANE_TAPER_WAYPOINTS = 4;

    private static final Map<MinecraftServer, RtsSharedPath> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<Long, Entry> active = new HashMap<>();

    private RtsSharedPath() {
    }

    public static RtsSharedPath get(MinecraftServer server) {
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(server, key -> new RtsSharedPath());
        }
    }

    public Join joinOrSubmit(ServerLevel level, BlockPos from, BlockPos stand, int padding, int laneSeed) {
        long key = stand.asLong();
        long now = level.getGameTime();
        Entry entry = active.get(key);
        if (entry != null && now - entry.createdAt() > ENTRY_TTL_TICKS && entry.future().isDone()) {
            active.remove(key);
            entry = null;
        }
        if (entry != null && entry.origin().distSqr(from) > JOIN_RADIUS_SQR) {
            entry = null;
        }
        if (entry != null && entry.future().isDone()) {
            NpcPathfinder.PathResult result = peek(entry.future());
            if (result == null || result.waypoints().size() < 2) {
                active.remove(key);
                entry = null;
            } else if (entry.lanes() == null) {
                entry = new Entry(entry.future(), entry.origin(), entry.createdAt(),
                        buildLanes(level, result.waypoints()));
                active.put(key, entry);
            }
        }
        if (entry != null && entry.lanes() != null) {
            List<NpcPathfinder.Waypoint> lane =
                    entry.lanes().get(Math.floorMod(laneSeed, entry.lanes().size()));
            return new Join(null, lane, true);
        }
        if (entry != null) {
            return new Join(entry.future(), null, true);
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return null;
        }
        NpcPathfinder.Session session = new NpcPathfinder.Session(from, stand);
        Future<NpcPathfinder.PathResult> future =
                repository.submitSessionSearch(level, session, from, stand, padding);
        if (future == null) {
            return null;
        }
        active.put(key, new Entry(future, from, now, null));
        return new Join(future, null, false);
    }

    private static List<List<NpcPathfinder.Waypoint>> buildLanes(ServerLevel level,
                                                                 List<NpcPathfinder.Waypoint> base) {
        int size = base.size();
        List<List<NpcPathfinder.Waypoint>> lanes = new ArrayList<>(LANE_OFFSETS.length);
        for (double offset : LANE_OFFSETS) {
            List<NpcPathfinder.Waypoint> lane = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                NpcPathfinder.Waypoint waypoint = base.get(i);
                BlockPos pos = waypoint.pos();
                BlockPos shifted = pos;
                if (offset != 0.0D
                        && waypoint.move() != NpcPathfinder.Waypoint.Move.CLIMB
                        && waypoint.move() != NpcPathfinder.Waypoint.Move.DROP) {
                    BlockPos prev = base.get(Math.max(0, i - 1)).pos();
                    BlockPos next = base.get(Math.min(size - 1, i + 1)).pos();
                    double dx = next.getX() - prev.getX();
                    double dz = next.getZ() - prev.getZ();
                    double length = Math.sqrt(dx * dx + dz * dz);
                    if (length > 1.0E-3D) {
                        double taper = Math.min(1.0D, (size - 1 - i) / (double) LANE_TAPER_WAYPOINTS);
                        double ox = -dz / length * offset * taper;
                        double oz = dx / length * offset * taper;
                        BlockPos candidate = BlockPos.containing(
                                pos.getX() + 0.5D + ox, pos.getY(), pos.getZ() + 0.5D + oz);
                        if (candidate.equals(pos) || walkableAt(level, candidate)) {
                            shifted = candidate;
                        }
                    }
                }
                lane.add(new NpcPathfinder.Waypoint(shifted, waypoint.move()));
            }
            lanes.add(lane);
        }
        return lanes;
    }

    private static boolean walkableAt(ServerLevel level, BlockPos pos) {
        BlockState floor = level.getBlockState(pos.below());
        if (!ChunkSnapshot.isWalkableFloor(floor)) {
            return false;
        }
        return ChunkSnapshot.isPassable(level.getBlockState(pos))
                && ChunkSnapshot.isPassable(level.getBlockState(pos.above()));
    }

    private NpcPathfinder.PathResult peek(Future<NpcPathfinder.PathResult> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }
}
