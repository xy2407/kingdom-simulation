package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;

/**
 * 快照 A* 寻路入口：解析搜索窗口、提交搜索、拼装结果与诊断输出。
 */
public final class NpcPathfinder {

    public record Waypoint(BlockPos pos, Move move) {
        public enum Move { WALK, STEP_UP, DROP, CLIMB }
    }

    static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    static final int PADDING = 8;

    public static final int DEFAULT_PADDING = PADDING;

    public static final int[] PADDING_STAGES = {8, 16, 32};

    static final int MAX_RANGE = 96;

    public static final int SEGMENT_RANGE = MAX_RANGE;

    static final int SEGMENT_RANGE_MIN = 16;

    static final int SEGMENT_Y_SPAN = 16;

    static final int SNAP_RANGE = 1;

    static final int MAX_NODES = 72000;

    static final int NEAREST_SAMPLE = 24;

    static final int Y_SLACK = 16;

    public static int paddingOf(int stage) {
        if (stage <= 0) {
            return PADDING_STAGES[0];
        }
        return PADDING_STAGES[Math.min(stage, PADDING_STAGES.length - 1)];
    }

    static int[] bounds(BlockPos start, BlockPos end, int padding) {
        int minX = Math.max(Math.min(start.getX(), end.getX()) - padding, start.getX() - MAX_RANGE);
        int maxX = Math.min(Math.max(start.getX(), end.getX()) + padding, start.getX() + MAX_RANGE);
        int minZ = Math.max(Math.min(start.getZ(), end.getZ()) - padding, start.getZ() - MAX_RANGE);
        int maxZ = Math.min(Math.max(start.getZ(), end.getZ()) + padding, start.getZ() + MAX_RANGE);
        return new int[] {minX, maxX, minZ, maxZ};
    }

    private NpcPathfinder() {
    }

    public static List<Waypoint> find(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        return find(repository, dimension, start, end, PADDING);
    }

    public record PathResult(List<Waypoint> waypoints, int missingChunks) {
        public PathResult {
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        }
    }

    public record DebugTrace(BlockPos start, BlockPos requested, int snappedStart, int snappedEnd,
                             int padding, int missingChunks, int visited,
                             int minX, int maxX, int minZ, int maxZ,
                             int reachMinY, int reachMaxY,
                             List<BlockPos> nearest, List<Waypoint> waypoints) {

        public DebugTrace {
            nearest = nearest == null ? List.of() : List.copyOf(nearest);
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        }

        public boolean snapped() {
            return snappedStart != Integer.MIN_VALUE && snappedEnd != Integer.MIN_VALUE;
        }

        public boolean exhausted() {
            return waypoints.isEmpty() && visited >= MAX_NODES;
        }
    }

    public static DebugTrace trace(SnapshotRepository repository, ServerLevel level,
                                   BlockPos start, BlockPos end, int padding) {
        int[] bounds = bounds(start, end, padding);
        if (repository == null || level == null) {
            return new DebugTrace(start, end, Integer.MIN_VALUE, Integer.MIN_VALUE, padding,
                    0, 0, bounds[0], bounds[1], bounds[2], bounds[3], 0, 0, List.of(), List.of());
        }
        ResourceKey<Level> dimension = level.dimension();
        repository.buildNow(level, bounds[0] >> 4, bounds[1] >> 4, bounds[2] >> 4, bounds[3] >> 4);
        SnapshotView view = SnapshotView.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        int snappedStart = view.snap(start);
        int snappedEnd = view.snap(end);
        if (snappedStart == Integer.MIN_VALUE || snappedEnd == Integer.MIN_VALUE) {
            return new DebugTrace(start, end, snappedStart, snappedEnd, padding, view.missingChunks(),
                    0, bounds[0], bounds[1], bounds[2], bounds[3], 0, 0, List.of(), List.of());
        }
        AStarSearch search = new AStarSearch(view, start.getX(), snappedStart, start.getZ(),
                end.getX(), snappedEnd, end.getZ());
        List<Waypoint> waypoints = search.run();
        int reachMinY = Integer.MAX_VALUE;
        int reachMaxY = Integer.MIN_VALUE;
        List<BlockPos> reached = new ArrayList<>(search.nodes.size());
        for (Node node : search.nodes.values()) {
            if (node.g == Float.MAX_VALUE) {
                continue;
            }
            reachMinY = Math.min(reachMinY, node.y);
            reachMaxY = Math.max(reachMaxY, node.y);
            reached.add(new BlockPos(node.x, node.y, node.z));
        }
        BlockPos goal = new BlockPos(end.getX(), snappedEnd, end.getZ());
        reached.sort(Comparator.comparingDouble(pos -> pos.distSqr(goal)));
        List<BlockPos> nearest = reached.subList(0, Math.min(NEAREST_SAMPLE, reached.size()));
        return new DebugTrace(start, end, snappedStart, snappedEnd, padding, view.missingChunks(),
                search.visited, bounds[0], bounds[1], bounds[2], bounds[3],
                reachMinY, reachMaxY, nearest, waypoints);
    }

    public static BlockPos midway(SnapshotRepository repository, ResourceKey<Level> dimension,
                                  BlockPos start, BlockPos end, int padding) {
        if (repository == null) {
            return null;
        }
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= SEGMENT_RANGE) {
            return null;
        }
        int[] bounds = bounds(start, end, padding);
        SnapshotView view = SnapshotView.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        for (int step = SEGMENT_RANGE; step >= SEGMENT_RANGE_MIN; step -= 3) {
            double t = step / horizontal;
            int x = (int) Math.floor(start.getX() + 0.5D + dx * t);
            int z = (int) Math.floor(start.getZ() + 0.5D + dz * t);
            for (int y = start.getY() + SEGMENT_Y_SPAN;
                 y >= start.getY() - SEGMENT_Y_SPAN; y--) {
                if (view.standable(x, y, z)) {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    public static List<Waypoint> find(SnapshotRepository repository, ResourceKey<Level> dimension,
                                      BlockPos start, BlockPos end, int padding) {
        return findWithStats(repository, dimension, start, end, padding).waypoints();
    }

    public static PathResult findWithStats(SnapshotRepository repository, ResourceKey<Level> dimension,
                                           BlockPos start, BlockPos end, int padding) {
        if (repository == null) {
            return new PathResult(List.of(), 0);
        }
        int[] bounds = bounds(start, end, padding);
        SnapshotView view = SnapshotView.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        int startY = view.snap(start);
        int endY = view.snap(end);
        if (startY == Integer.MIN_VALUE || endY == Integer.MIN_VALUE) {
            return new PathResult(List.of(), view.missingChunks());
        }
        if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
            return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), startY, start.getZ()),
                    Waypoint.Move.WALK)), view.missingChunks());
        }
        List<Waypoint> waypoints = new AStarSearch(view, start.getX(), startY, start.getZ(),
                end.getX(), endY, end.getZ()).run();
        return new PathResult(waypoints, view.missingChunks());
    }

    public static boolean isViable(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        if (repository == null) {
            return false;
        }
        return hasStandableLayer(repository, dimension, start) && hasStandableLayer(repository, dimension, end);
    }

    private static boolean hasStandableLayer(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos pos) {
        ChunkSnapshot snapshot = repository.getSnapshot(dimension, pos.getX() >> 4, pos.getZ() >> 4);
        if (snapshot == null) {
            return true;
        }
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        for (int dy = 0; dy <= SNAP_RANGE; dy++) {
            if (snapshot.standable(lx, pos.getY() - dy, lz)) {
                return true;
            }
            if (dy > 0 && snapshot.standable(lx, pos.getY() + dy, lz)) {
                return true;
            }
        }
        return false;
    }

    public static void requestChunks(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        requestChunks(repository, dimension, start, end, PADDING);
    }

    public static void requestChunks(SnapshotRepository repository, ResourceKey<Level> dimension,
                                     BlockPos start, BlockPos end, int padding) {
        if (repository == null) {
            return;
        }
        int[] bounds = bounds(start, end, padding);
        int centerX = start.getX() >> 4;
        int centerZ = start.getZ() >> 4;
        List<ChunkPos> positions = new ArrayList<>();
        for (int cx = bounds[0] >> 4; cx <= bounds[1] >> 4; cx++) {
            for (int cz = bounds[2] >> 4; cz <= bounds[3] >> 4; cz++) {
                positions.add(new ChunkPos(cx, cz));
            }
        }
        positions.sort(Comparator.comparingLong(pos -> {
            long dx = (long) pos.x - centerX;
            long dz = (long) pos.z - centerZ;
            return dx * dx + dz * dz;
        }));
        for (ChunkPos pos : positions) {
            repository.enqueue(dimension, pos);
        }
    }

    public static final class Session {
        private final BlockPos start;
        private final BlockPos end;
        private boolean preferRoad = true;

        public Session(BlockPos start, BlockPos end) {
            this.start = start.immutable();
            this.end = end.immutable();
        }

        public boolean matches(BlockPos start, BlockPos end) {
            return this.start.equals(start) && this.end.equals(end);
        }

        public Session preferRoad(boolean value) {
            this.preferRoad = value;
            return this;
        }

        public PathResult advance(SnapshotRepository repository, ResourceKey<Level> dimension, int padding) {
            int[] bounds = bounds(start, end, padding);
            SnapshotView view = SnapshotView.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
            int snappedStart = view.snap(start);
            int snappedEnd = view.snap(end);
            if (snappedStart == Integer.MIN_VALUE || snappedEnd == Integer.MIN_VALUE) {
                return new PathResult(List.of(), view.missingChunks());
            }
            if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
                return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), snappedStart, start.getZ()),
                        Waypoint.Move.WALK)), view.missingChunks());
            }
            AStarSearch next = new AStarSearch(view, start.getX(), snappedStart, start.getZ(),
                    end.getX(), snappedEnd, end.getZ(), preferRoad);
            return new PathResult(next.run(), view.missingChunks());
        }
    }
}
