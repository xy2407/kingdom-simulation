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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * 快照 A* 寻路，四向扩展并可向下跌落；只读快照数据，可在后台线程执行
 */
public final class NpcPathfinder {
    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final int PADDING = 8;
    private static final int MAX_RANGE = 96;
    private static final int SNAP_RANGE = 4;
    private static final int DOWNWARD_RANGE = 4;
    private static final int MAX_NODES = 8192;
    private static final double HEURISTIC_WEIGHT = 1.2D;
    private static final double COST_STEP_UP = 1.1D;
    private static final double COST_STEP_DOWN_STEPPED = 1.2D;
    private static final double COST_STEP_DOWN_DROP = 2.4D;
    private static final double COST_DROP_FROM_SCAFFOLD = 12.0D;

    private NpcPathfinder() {
    }

    public static List<BlockPos> find(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        if (repository == null) {
            return List.of();
        }
        int[] bounds = bounds(start, end);
        View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
        int startY = view.snap(start);
        int endY = view.snap(end);
        if (startY == Integer.MIN_VALUE || endY == Integer.MIN_VALUE) {
            return List.of();
        }
        if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
            return List.of(new BlockPos(start.getX(), startY, start.getZ()));
        }
        return new Search(view, end.getX(), endY, end.getZ()).run(start.getX(), startY, start.getZ());
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
            if (snapshot.standable(lx, pos.getY() + dy, lz)) {
                return true;
            }
            if (dy > 0 && snapshot.standable(lx, pos.getY() - dy, lz)) {
                return true;
            }
        }
        return false;
    }

    public static void requestChunks(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        if (repository == null) {
            return;
        }
        int[] bounds = bounds(start, end);
        for (int cx = bounds[0] >> 4; cx <= bounds[1] >> 4; cx++) {
            for (int cz = bounds[2] >> 4; cz <= bounds[3] >> 4; cz++) {
                repository.enqueue(dimension, new ChunkPos(cx, cz));
            }
        }
    }

    private static int[] bounds(BlockPos start, BlockPos end) {
        int minX = Math.max(Math.min(start.getX(), end.getX()) - PADDING, start.getX() - MAX_RANGE);
        int maxX = Math.min(Math.max(start.getX(), end.getX()) + PADDING, start.getX() + MAX_RANGE);
        int minZ = Math.max(Math.min(start.getZ(), end.getZ()) - PADDING, start.getZ() - MAX_RANGE);
        int maxZ = Math.min(Math.max(start.getZ(), end.getZ()) + PADDING, start.getZ() + MAX_RANGE);
        return new int[]{minX, maxX, minZ, maxZ};
    }

    private static Node create(int x, int y, int z, float g) {
        Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        node.g = g;
        return node;
    }

    private static double stepCost(int fromY, int toY) {
        int dy = toY - fromY;
        if (dy > 0) {
            return 1.0D + COST_STEP_UP * dy;
        }
        if (dy < 0) {
            return 1.0D + (dy == -1 ? COST_STEP_DOWN_STEPPED : COST_STEP_DOWN_DROP) * Math.abs(dy);
        }
        return 1.0D;
    }

    private static double scaffoldDropCost(int dy) {
        return 1.0D + COST_DROP_FROM_SCAFFOLD * Math.abs(dy);
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

        private View(ChunkSnapshot[][] chunks, int minChunkX, int minChunkZ) {
            this.chunks = chunks;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
        }

        static View create(SnapshotRepository repository, ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;
            ChunkSnapshot[][] chunks = new ChunkSnapshot[maxChunkX - minChunkX + 1][maxChunkZ - minChunkZ + 1];
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    chunks[cx - minChunkX][cz - minChunkZ] = repository.getSnapshot(dimension, cx, cz);
                }
            }
            return new View(chunks, minChunkX, minChunkZ);
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

        boolean climbable(int x, int y, int z) {
            int i = (x >> 4) - minChunkX;
            int j = (z >> 4) - minChunkZ;
            if (i < 0 || j < 0 || i >= chunks.length || j >= chunks[i].length) {
                return false;
            }
            ChunkSnapshot snapshot = chunks[i][j];
            return snapshot != null && snapshot.climbable(x & 15, y, z & 15);
        }

        int snap(BlockPos pos) {
            for (int dy = 0; dy <= SNAP_RANGE; dy++) {
                if (standable(pos.getX(), pos.getY() + dy, pos.getZ())) {
                    return pos.getY() + dy;
                }
                if (dy > 0 && standable(pos.getX(), pos.getY() - dy, pos.getZ())) {
                    return pos.getY() - dy;
                }
            }
            return Integer.MIN_VALUE;
        }
    }

    private static final class Search {
        private final View view;
        private final int endX;
        private final int endY;
        private final int endZ;
        private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

        private Search(View view, int endX, int endY, int endZ) {
            this.view = view;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
        }

        List<BlockPos> run(int startX, int startY, int startZ) {
            Node start = create(startX, startY, startZ, 0.0F);
            Node goal = create(endX, endY, endZ, Float.MAX_VALUE);
            nodes.put(BlockPos.asLong(startX, startY, startZ), start);
            nodes.put(BlockPos.asLong(endX, endY, endZ), goal);
            start.h = (float) (heuristic(start, endX, endY, endZ) * HEURISTIC_WEIGHT);
            start.f = start.g + start.h;
            open.add(start);
            int visited = 0;
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
            List<BlockPos> waypoints = new ArrayList<>();
            for (Node node = found; node != null; node = node.cameFrom) {
                waypoints.add(new BlockPos(node.x, node.y, node.z));
            }
            Collections.reverse(waypoints);
            return waypoints;
        }

        private void expand(Node current) {
            for (Direction direction : HORIZONTALS) {
                int x = current.x + direction.getStepX();
                int z = current.z + direction.getStepZ();
                offer(current, x, current.y, z);
                offer(current, x, current.y + 1, z);
                offer(current, x, current.y - 1, z);
                for (int dy = -2; dy >= -DOWNWARD_RANGE; dy--) {
                    if (view.standable(x, current.y + dy, z)) {
                        offer(current, x, current.y + dy, z);
                        break;
                    }
                }
            }
            if (view.climbable(current.x, current.y, current.z)) {
                offer(current, current.x, current.y + 1, current.z);
                offer(current, current.x, current.y - 1, current.z);
            }
        }

        private void offer(Node current, int x, int y, int z) {
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
            float g = current.g + (float) (y < current.y && view.climbable(current.x, current.y, current.z)
                    ? scaffoldDropCost(y - current.y)
                    : stepCost(current.y, y));
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
