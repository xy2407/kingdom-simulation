package com.xy2407.kingdom.common.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * 快照 A* 寻路，四向扩展并可向下跌落；只读快照数据，可在后台线程执行
 */
public final class NpcPathfinder {

    public record Waypoint(BlockPos pos, Move move) {
        public enum Move { WALK, STEP_UP, DROP, CLIMB }
    }

    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final int PADDING = 8;

    public static final int DEFAULT_PADDING = PADDING;

    public static final int[] PADDING_STAGES = {8, 16, 32};

    public static int paddingOf(int stage) {
        if (stage <= 0) {
            return PADDING_STAGES[0];
        }
        return PADDING_STAGES[Math.min(stage, PADDING_STAGES.length - 1)];
    }

    private static final int MAX_RANGE = 96;
    public static final int SEGMENT_RANGE = MAX_RANGE;
    private static final int SEGMENT_RANGE_MIN = 16;
    private static final int SEGMENT_Y_SPAN = 16;
    private static final int SNAP_RANGE = 1;
    private static final int DOWNWARD_RANGE = 4;
    private static final int MAX_NODES = 32768;
    private static final int NEAREST_SAMPLE = 24;
    private static final double HEURISTIC_WEIGHT = 1.05D;
    private static final int Y_SLACK = 16;
    private static final double COST_STEP_UP = 1.1D;
    private static final double COST_STEP_DOWN_STEPPED = 1.2D;
    private static final double COST_STEP_DOWN_DROP = 8.0D;
    private static final double STEP_THRESHOLD = 0.6D;

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
        View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        int snappedStart = view.snap(start);
        int snappedEnd = view.snap(end);
        if (snappedStart == Integer.MIN_VALUE || snappedEnd == Integer.MIN_VALUE) {
            return new DebugTrace(start, end, snappedStart, snappedEnd, padding, view.missingChunks(),
                    0, bounds[0], bounds[1], bounds[2], bounds[3], 0, 0, List.of(), List.of());
        }
        Search search = new Search(view, start.getX(), snappedStart, start.getZ(),
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
        View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        for (int step = SEGMENT_RANGE; step >= SEGMENT_RANGE_MIN; step -= 3) {
            double t = step / horizontal;
            int x = (int) Math.floor(start.getX() + 0.5D + dx * t);
            int z = (int) Math.floor(start.getZ() + 0.5D + dz * t);
            for (int y = start.getY() + SEGMENT_Y_SPAN; y >= start.getY() - SEGMENT_Y_SPAN; y--) {
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
        View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        int startY = view.snap(start);
        int endY = view.snap(end);
        if (startY == Integer.MIN_VALUE || endY == Integer.MIN_VALUE) {
            return new PathResult(List.of(), view.missingChunks());
        }
        if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
            return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), startY, start.getZ()),
                    Waypoint.Move.WALK)), view.missingChunks());
        }
        List<Waypoint> waypoints = new Search(view, start.getX(), startY, start.getZ(),
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

    private static int[] bounds(BlockPos start, BlockPos end, int padding) {
        int minX = Math.max(Math.min(start.getX(), end.getX()) - padding, start.getX() - MAX_RANGE);
        int maxX = Math.min(Math.max(start.getX(), end.getX()) + padding, start.getX() + MAX_RANGE);
        int minZ = Math.max(Math.min(start.getZ(), end.getZ()) - padding, start.getZ() - MAX_RANGE);
        int maxZ = Math.min(Math.max(start.getZ(), end.getZ()) + padding, start.getZ() + MAX_RANGE);
        return new int[]{minX, maxX, minZ, maxZ};
    }

    private static Node create(int x, int y, int z, float g) {
        Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        node.g = g;
        return node;
    }

    private static double stepCost(double dh) {
        if (dh > STEP_THRESHOLD) {
            return 1.0D + COST_STEP_UP * dh;
        }
        if (dh < -STEP_THRESHOLD) {
            return 1.0D + (-dh <= 1.0D ? COST_STEP_DOWN_STEPPED : COST_STEP_DOWN_DROP) * Math.abs(dh);
        }
        return 1.0D;
    }

    private static double heightAt(View view, int x, int y, int z) {
        return y - (view.halfFloor(x, y, z) ? 0.5D : 0.0D);
    }

    private static double heuristic(Node from, int x, int y, int z) {
        double dx = (double) from.x - x;
        double dy = (double) from.y - y;
        double dz = (double) from.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class View {
        private final ChunkSnapshot[][] chunks;
        private final int minChunkX;
        private final int minChunkZ;
        private final int missingChunks;

        private View(ChunkSnapshot[][] chunks, int minChunkX, int minChunkZ, int missingChunks) {
            this.chunks = chunks;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.missingChunks = missingChunks;
        }

        int missingChunks() {
            return missingChunks;
        }

        static View create(SnapshotRepository repository, ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;
            ChunkSnapshot[][] chunks = new ChunkSnapshot[maxChunkX - minChunkX + 1][maxChunkZ - minChunkZ + 1];
            int missing = 0;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    ChunkSnapshot snapshot = repository.getSnapshot(dimension, cx, cz);
                    chunks[cx - minChunkX][cz - minChunkZ] = snapshot;
                    if (snapshot == null) {
                        missing++;
                    }
                }
            }
            return new View(chunks, minChunkX, minChunkZ, missing);
        }

        boolean standable(int x, int y, int z) {
            int i = (x >> 4) - minChunkX;
            int j = (z >> 4) - minChunkZ;
            if (i < 0 || j < 0 || i >= chunks.length || j >= chunks[i].length) {
                return false;
            }
            ChunkSnapshot snapshot = chunks[i][j];
            return snapshot != null && snapshot.standable(x & 15, y, z & 15);
        }

        boolean blocked(int x, int y, int z) {
            int i = (x >> 4) - minChunkX;
            int j = (z >> 4) - minChunkZ;
            if (i < 0 || j < 0 || i >= chunks.length || j >= chunks[i].length) {
                return true;
            }
            ChunkSnapshot snapshot = chunks[i][j];
            return snapshot == null || snapshot.blocked(x & 15, y, z & 15);
        }

        boolean climbable(int x, int y, int z) {
            int i = (x >> 4) - minChunkX;
            int j = (z >> 4) - minChunkZ;
            if (i < 0 || j < 0 || i >= chunks.length || j >= chunks[i].length) {
                return false;
            }
            ChunkSnapshot snapshot = chunks[i][j];
            return snapshot != null && snapshot.climbable(x & 15, y, z & 15);
        }

        boolean halfFloor(int x, int y, int z) {
            int i = (x >> 4) - minChunkX;
            int j = (z >> 4) - minChunkZ;
            if (i < 0 || j < 0 || i >= chunks.length || j >= chunks[i].length) {
                return false;
            }
            ChunkSnapshot snapshot = chunks[i][j];
            return snapshot != null && snapshot.halfFloor(x & 15, y, z & 15);
        }

        int snap(BlockPos pos) {
            for (int dy = 0; dy <= SNAP_RANGE; dy++) {
                if (standable(pos.getX(), pos.getY() - dy, pos.getZ())) {
                    return pos.getY() - dy;
                }
                if (dy > 0 && standable(pos.getX(), pos.getY() + dy, pos.getZ())) {
                    return pos.getY() + dy;
                }
            }
            return Integer.MIN_VALUE;
        }
    }

    public static final class Session {
        private final BlockPos start;
        private final BlockPos end;

        public Session(BlockPos start, BlockPos end) {
            this.start = start.immutable();
            this.end = end.immutable();
        }

        public boolean matches(BlockPos start, BlockPos end) {
            return this.start.equals(start) && this.end.equals(end);
        }

        public PathResult advance(SnapshotRepository repository, ResourceKey<Level> dimension, int padding) {
            int[] bounds = bounds(start, end, padding);
            View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
            int snappedStart = view.snap(start);
            int snappedEnd = view.snap(end);
            if (snappedStart == Integer.MIN_VALUE || snappedEnd == Integer.MIN_VALUE) {
                return new PathResult(List.of(), view.missingChunks());
            }
            if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
                return new PathResult(List.of(new Waypoint(new BlockPos(start.getX(), snappedStart, start.getZ()),
                        Waypoint.Move.WALK)), view.missingChunks());
            }
            Search next = new Search(view, start.getX(), snappedStart, start.getZ(),
                    end.getX(), snappedEnd, end.getZ());
            return new PathResult(next.run(), view.missingChunks());
        }
    }

    private static final class Search {
        private View view;
        private final int startX;
        private final int startY;
        private final int startZ;
        private final int endX;
        private int endY;
        private final int endZ;
        private int minY;
        private int maxY;
        private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
        private int visited;
        private long goalKey;

        private Search(View view, int startX, int startY, int startZ, int endX, int endY, int endZ) {
            this.view = view;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
            clampY();
        }

        private void clampY() {
            this.minY = Math.min(startY, endY) - Y_SLACK;
            this.maxY = Math.max(startY, endY) + Y_SLACK;
        }

        List<Waypoint> run() {
            Node start = create(startX, startY, startZ, 0.0F);
            Node goal = create(endX, endY, endZ, Float.MAX_VALUE);
            nodes.put(BlockPos.asLong(startX, startY, startZ), start);
            goalKey = BlockPos.asLong(endX, endY, endZ);
            nodes.put(goalKey, goal);
            start.h = (float) (heuristic(start, endX, endY, endZ) * HEURISTIC_WEIGHT);
            start.f = start.g + start.h;
            open.add(start);
            return drain();
        }

        private List<Waypoint> drain() {
            Node goal = nodes.get(goalKey);
            Node found = null;
            while (!open.isEmpty() && visited < MAX_NODES) {
                Node current = open.poll();
                if (current.closed) {
                    continue;
                }
                current.closed = true;
                visited++;
                if (current == goal) {
                    found = current;
                    break;
                }
                expand(current);
            }
            if (found == null) {
                return List.of();
            }
            List<Waypoint> waypoints = new ArrayList<>();
            for (Node node = found; node != null; node = node.cameFrom) {
                Node from = node.cameFrom;
                waypoints.add(new Waypoint(new BlockPos(node.x, node.y, node.z),
                        from == null ? Waypoint.Move.WALK : moveOf(from, node)));
            }
            Collections.reverse(waypoints);
            return waypoints;
        }

        private Waypoint.Move moveOf(Node from, Node to) {
            double dh = heightAt(view, to.x, to.y, to.z) - heightAt(view, from.x, from.y, from.z);
            if (dh > STEP_THRESHOLD) {
                boolean sameColumn = from.x == to.x && from.z == to.z;
                return sameColumn ? Waypoint.Move.CLIMB : Waypoint.Move.STEP_UP;
            }
            if (dh < -STEP_THRESHOLD) {
                boolean onLadder = view.climbable(from.x, from.y, from.z)
                        || view.climbable(to.x, to.y, to.z);
                return onLadder ? Waypoint.Move.CLIMB : Waypoint.Move.DROP;
            }
            return Waypoint.Move.WALK;
        }

        private void expand(Node current) {
            if (view.climbable(current.x, current.y, current.z)) {
                offer(current, current.x, current.y + 1, current.z);
                offer(current, current.x, current.y - 1, current.z);
            }
            for (Direction direction : HORIZONTALS) {
                int x = current.x + direction.getStepX();
                int z = current.z + direction.getStepZ();
                offer(current, x, current.y, z);
                offer(current, x, current.y + 1, z);
                for (int dy = -1; dy >= -DOWNWARD_RANGE; dy--) {
                    int y = current.y + dy;
                    if (view.blocked(x, y, z)) {
                        break;
                    }
                    if (view.standable(x, y, z)) {
                        offer(current, x, y, z);
                        break;
                    }
                }
            }
        }

        private void offer(Node current, int x, int y, int z) {
            if (y < minY || y > maxY) {
                return;
            }
            if (!view.standable(x, y, z)) {
                return;
            }
            long key = BlockPos.asLong(x, y, z);
            Node node = nodes.get(key);
            if (node == null) {
                node = create(x, y, z, Float.MAX_VALUE);
                nodes.put(key, node);
            }
            if (node.closed) {
                return;
            }
            double dh = heightAt(view, x, y, z) - heightAt(view, current.x, current.y, current.z);
            float g = current.g + (float) stepCost(dh);
            if (g >= node.g) {
                return;
            }
            node.g = g;
            node.cameFrom = current;
            node.h = (float) (heuristic(node, endX, endY, endZ) * HEURISTIC_WEIGHT);
            node.f = node.g + node.h;
            open.add(node);
        }
    }
}
