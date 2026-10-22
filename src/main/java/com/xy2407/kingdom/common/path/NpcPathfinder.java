package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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

    public static final int SEGMENT_VERTICAL = 32;

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
        repository.requestUnits(dimension, start, end, padding);
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
        double dy = end.getY() - start.getY();
        double dz = end.getZ() - start.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= SEGMENT_RANGE && Math.abs(dy) <= SEGMENT_VERTICAL) {
            return null;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int limit = Math.abs(dy) > SEGMENT_VERTICAL
                ? Math.min(SEGMENT_VERTICAL, (int) distance)
                : Math.min(SEGMENT_RANGE, (int) distance);
        int[] bounds = bounds(start, end, padding);
        SnapshotView view = SnapshotView.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        for (int step = limit; step >= SEGMENT_RANGE_MIN; step -= 3) {
            double t = step / distance;
            int x = (int) Math.floor(start.getX() + 0.5D + dx * t);
            int y = (int) Math.floor(start.getY() + dy * t);
            int z = (int) Math.floor(start.getZ() + 0.5D + dz * t);
            for (int offset = 0; offset <= SEGMENT_Y_SPAN; offset++) {
                if (view.standable(x, y - offset, z)) {
                    return new BlockPos(x, y - offset, z);
                }
                if (offset > 0 && view.standable(x, y + offset, z)) {
                    return new BlockPos(x, y + offset, z);
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
        if (start.getX() == end.getX() && start.getZ() == end.getZ()
                && Math.abs(startY - endY) <= 2) {
            return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), startY, start.getZ()),
                    Waypoint.Move.WALK)), view.missingChunks());
        }
        List<Waypoint> waypoints = new AStarSearch(view, start.getX(), startY, start.getZ(),
                end.getX(), endY, end.getZ()).run();
        return new PathResult(waypoints, view.missingChunks());
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
            if (start.getX() == end.getX() && start.getZ() == end.getZ()
                    && Math.abs(snappedStart - snappedEnd) <= 2) {
                return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), snappedStart, start.getZ()),
                        Waypoint.Move.WALK)), view.missingChunks());
            }
            AStarSearch next = new AStarSearch(view, start.getX(), snappedStart, start.getZ(),
                    end.getX(), snappedEnd, end.getZ(), preferRoad);
            return new PathResult(next.run(), view.missingChunks());
        }
    }
}
