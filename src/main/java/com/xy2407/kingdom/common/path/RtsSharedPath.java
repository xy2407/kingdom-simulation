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
 * RTS 群组共享路径缓存：同目的地共用一次 A*，完成后生成多车道副本并按占用数分配成员。
 */
public final class RtsSharedPath {

    public record Join(Future<NpcPathfinder.PathResult> future,
                       List<NpcPathfinder.Waypoint> readyPath, boolean shared) {
    }

    private record Entry(Future<NpcPathfinder.PathResult> future, BlockPos origin, long createdAt,
                         List<List<NpcPathfinder.Waypoint>> lanes, int[] laneUse) {
    }

    private static final double JOIN_RADIUS_SQR = 24.0D * 24.0D;
    private static final long ENTRY_TTL_TICKS = 600L;
    private static final double[] LANE_OFFSETS = {0.0D, 0.7D, -0.7D, 1.4D, -1.4D, 2.1D, -2.1D};
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

    public static void clearAll() {
        synchronized (INSTANCES) {
            for (RtsSharedPath instance : INSTANCES.values()) {
                instance.active.clear();
            }
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
                List<List<NpcPathfinder.Waypoint>> lanes = buildLanes(level, result.waypoints());
                entry = new Entry(entry.future(), entry.origin(), entry.createdAt(),
                        lanes, new int[lanes.size()]);
                active.put(key, entry);
            }
        }
        if (entry != null && entry.lanes() != null) {
            int index = pickLane(entry, laneSeed);
            entry.laneUse()[index]++;
            return new Join(null, entry.lanes().get(index), true);
        }
        if (entry != null) {
            return new Join(entry.future(), null, true);
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return null;
        }
        NpcPathfinder.Session session = new NpcPathfinder.Session(from, stand).preferRoad(false);
        Future<NpcPathfinder.PathResult> future =
                repository.submitSessionSearch(level, session, from, stand, padding);
        if (future == null) {
            return null;
        }
        active.put(key, new Entry(future, from, now, null, null));
        return new Join(future, null, false);
    }

    private static int pickLane(Entry entry, int laneSeed) {
        int[] use = entry.laneUse();
        int best = 0;
        for (int i = 1; i < use.length; i++) {
            if (use[i] < use[best]
                    || (use[i] == use[best] && tieBreak(i, best, laneSeed))) {
                best = i;
            }
        }
        return best;
    }

    private static boolean tieBreak(int a, int b, int laneSeed) {
        int cmp = Double.compare(Math.abs(LANE_OFFSETS[a]), Math.abs(LANE_OFFSETS[b]));
        if (cmp != 0) {
            return cmp < 0;
        }
        return Math.floorMod(laneSeed + a, 2) < Math.floorMod(laneSeed + b, 2);
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
                    double taper = Math.min(1.0D, (size - 1 - i) / (double) LANE_TAPER_WAYPOINTS);
                    BlockPos candidate = shift(pos, prev, next, offset * taper);
                    if (!candidate.equals(pos) && !walkableAt(level, candidate)) {
                        BlockPos mirrored = shift(pos, prev, next, -offset * taper);
                        if (!mirrored.equals(pos) && walkableAt(level, mirrored)) {
                            candidate = mirrored;
                        }
                    }
                    if (candidate.equals(pos) || walkableAt(level, candidate)) {
                        shifted = candidate;
                    }
                }
                lane.add(new NpcPathfinder.Waypoint(shifted, waypoint.move()));
            }
            lanes.add(lane);
        }
        return lanes;
    }

    private static BlockPos shift(BlockPos pos, BlockPos prev, BlockPos next, double offset) {
        double dx = next.getX() - prev.getX();
        double dz = next.getZ() - prev.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0E-3D) {
            return pos;
        }
        return BlockPos.containing(
                pos.getX() + 0.5D - dz / length * offset,
                pos.getY(),
                pos.getZ() + 0.5D + dx / length * offset);
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
