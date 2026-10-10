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

    /** 给不关心分级的调用方用的缺省余量。 */
    public static final int DEFAULT_PADDING = PADDING;

    /**
     * 起终点包围盒的横向余量分级：4 → 12 → 24。
     * <p>
     * 余量同时决定入队构建哪些区块、以及 A* 能走多远。小余量搜索空间小、快，
     * 但绕远路时会撞出快照边界直接搜不到；所以先试最小的，不行再逐级放大，
     * 而不是一次就给到 24（那样每次搜索都要多建好几倍区块）。
     */
    public static final int[] PADDING_STAGES = {4, 12, 24};

    /** 取第 stage 档的余量，越界就取最后一档。 */
    public static int paddingOf(int stage) {
        if (stage <= 0) {
            return PADDING_STAGES[0];
        }
        return PADDING_STAGES[Math.min(stage, PADDING_STAGES.length - 1)];
    }

    private static final int MAX_RANGE = 96;
    private static final int SNAP_RANGE = 4;
    private static final int DOWNWARD_RANGE = 4;
    private static final int MAX_NODES = 8192;
    private static final double HEURISTIC_WEIGHT = 1.2D;
    /** 搜索允许偏离起终点高度的余量（格）：爬楼够用，又不会白探世界顶/底。 */
    private static final int Y_SLACK = 16;
    private static final double COST_STEP_UP = 1.1D;
    /** 下一格台阶：2.2，比平走贵一点，正常走楼梯。 */
    private static final double COST_STEP_DOWN_STEPPED = 1.2D;
    /**
     * 跳楼（一次落下 2 格以上）的单格代价。
     * <p>
     * 原来是 2.4，等于"下 x 格 ≈ 绕 2.4x 步"，多层建筑里绕到楼梯远超这个数，
     * 所以市民会为了省路直接从二楼跳下去。提到 8.0 之后是"绕 8x 步才划算"
     * （下 4 格 = 33，要绕 33 步才会选跳），跳楼只会在真的无路可走时出现。
     * <p>
     * 另外注意启发函数是三维欧氏距离，跳下去本身就让启发值下降，
     * 所以成本必须给得足够高才能压住这种倾向。
     */
    private static final double COST_STEP_DOWN_DROP = 8.0D;

    private NpcPathfinder() {
    }

    public static List<BlockPos> find(SnapshotRepository repository, ResourceKey<Level> dimension, BlockPos start, BlockPos end) {
        return find(repository, dimension, start, end, PADDING);
    }

    /**
     * 一次寻路的结果：航点，以及这次 View 覆盖范围内**快照还没建好**的区块数。
     * <p>
     * 后者用来区分失败原因 —— 搜不到时若还有缺失区块，那是"数据没到"，
     * 该做的是等构建完成再用同一档重试，而不是白白放大搜索余量。
     */
    public record PathResult(List<BlockPos> waypoints, int missingChunks) {
        public PathResult {
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        }
    }

    public static List<BlockPos> find(SnapshotRepository repository, ResourceKey<Level> dimension,
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
            return new PathResult(List.of(new BlockPos(start.getX(), startY, start.getZ())),
                    view.missingChunks());
        }
        List<BlockPos> waypoints = new Search(view, start.getX(), startY, start.getZ(),
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
        // 先原位、再优先向下 —— 目标点上方常常就是同 XZ 的另一层楼板，
        // 若先往上找，一楼的目标会被判成"在二楼"。
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
        for (int cx = bounds[0] >> 4; cx <= bounds[1] >> 4; cx++) {
            for (int cz = bounds[2] >> 4; cz <= bounds[3] >> 4; cz++) {
                repository.enqueue(dimension, new ChunkPos(cx, cz));
            }
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
        /** 覆盖范围内还没建好快照的区块数：>0 说明"搜不到"可能只是数据没到。 */
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

        int snap(BlockPos pos) {
            // 先原位、再优先向下。目标点上方常常就是同 XZ 的另一层楼板，
            // 原来先试 y+1 会把"一楼"的目标贴到二楼去 —— NPC 送到楼上后
            // 与真正的目标只差高度，水平判定却认为已到达，人就卡在那儿原地打转。
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

    /**
     * 可续跑的寻路会话：同一组起终点被反复请求（升档重试）时，
     * 保留上一次的搜索图（nodes + open 前沿），换个更大的范围接着搜，
     * 而不是从起点重算一遍。
     * <p>
     * 由调用方（{@code NpcEntity}）持有；同一个市民的请求是串行的
     * （上一个 future 没完成不会提交下一个），所以不需要额外加锁。
     */
    public static final class Session {
        private final BlockPos start;
        private final BlockPos end;
        private Search search;
        private int searchedPadding = -1;

        public Session(BlockPos start, BlockPos end) {
            this.start = start.immutable();
            this.end = end.immutable();
        }

        /** 起终点一致才允许续跑；换了目标必须重建会话。 */
        public boolean matches(BlockPos start, BlockPos end) {
            return this.start.equals(start) && this.end.equals(end);
        }

        /** 上一次实际用的余量档，调用方据此决定是否升档。 */
        public int searchedPadding() {
            return searchedPadding;
        }

        /**
         * 用给定余量推进一次搜索。
         * <p>
         * 余量变大 → 续跑（保留 g 值与前沿，最划算）。
         * 余量没变 → 是"同一档再试"，这时快照数据可能已经补齐，但旧图里
         * 那些当时判死的节点不会自己重探，所以重建搜索图重跑一遍。
         */
        public PathResult advance(SnapshotRepository repository, ResourceKey<Level> dimension, int padding) {
            int[] bounds = bounds(start, end, padding);
            View view = View.create(repository, dimension, bounds[0], bounds[1], bounds[2], bounds[3]);
            int snappedStart = view.snap(start);
            int snappedEnd = view.snap(end);
            if (snappedStart == Integer.MIN_VALUE || snappedEnd == Integer.MIN_VALUE) {
                // 起终点还没贴到地：这轮连图都建不起来，等数据补齐再来
                return new PathResult(List.of(), view.missingChunks());
            }
            if (start.getX() == end.getX() && start.getZ() == end.getZ()) {
                return new PathResult(List.of(new BlockPos(start.getX(), snappedStart, start.getZ())),
                        view.missingChunks());
            }
            if (search != null && padding > searchedPadding) {
                searchedPadding = padding;
                return new PathResult(search.resume(view, snappedEnd), view.missingChunks());
            }
            search = new Search(view, start.getX(), snappedStart, start.getZ(),
                    end.getX(), snappedEnd, end.getZ());
            searchedPadding = padding;
            return new PathResult(search.run(), view.missingChunks());
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
        /** 搜索的 Y 上下界：起终点高度 ± 余量之外的格子不可能更优，直接剪掉。 */
        private int minY;
        private int maxY;
        private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
        /** 累计展开数，跨续跑接着数，保证总工作量仍受 MAX_NODES 约束。 */
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

        /** 首轮：放起点与终点节点，开搜。 */
        List<BlockPos> run() {
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

        /**
         * 续跑：换成更大的快照范围后接着搜。
         * <p>
         * 保留 {@code nodes}（g 值与父指针）和 {@code open} 前沿，只把已 closed 的节点
         * 放回队列 —— 新补上的区块可能让原先判死的边界节点重新变通。放回不会退化成全量，
         * 因为 {@link #offer} 里的 {@code g >= node.g} 剪枝会让老区域直接跳过，
         * 真正重算的只有边界那一圈，而 expand 才是开销大头。
         */
        List<BlockPos> resume(View nextView, int snappedEndY) {
            this.view = nextView;
            if (snappedEndY != Integer.MIN_VALUE && snappedEndY != endY) {
                endY = snappedEndY;
                clampY();
                long newGoalKey = BlockPos.asLong(endX, endY, endZ);
                if (newGoalKey != goalKey) {
                    // 终点贴地高度变了，旧终点节点作废
                    nodes.remove(goalKey);
                    Node goal = create(endX, endY, endZ, Float.MAX_VALUE);
                    nodes.put(newGoalKey, goal);
                    goalKey = newGoalKey;
                }
            }
            for (Node node : nodes.values()) {
                if (node.closed) {
                    node.closed = false;
                    open.add(node);
                }
            }
            return drain();
        }

        private List<BlockPos> drain() {
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
            float g = current.g + (float) stepCost(current.y, y);
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
