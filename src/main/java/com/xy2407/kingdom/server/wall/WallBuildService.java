package com.xy2407.kingdom.server.wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.persistence.WallSchema;
import com.xy2407.kingdom.common.wall.WallBlock;
import com.xy2407.kingdom.common.wall.WallNode;
import com.xy2407.kingdom.common.wall.WallSegment;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.task.RegionTaskService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * 城墙施工：领地建筑工到场后按任务逐格放置，未到场的任务不推进
 */
public final class WallBuildService {

    private static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static final int SAVE_INTERVAL_TICKS = 100;

    private static final int SWING_INTERVAL_TICKS = 20;

    private static final int STALL_RELEASE_TICKS = 1200;

    private static final class Session {
        private final List<WallBlock> pending;
        private final int total;
        private int lastProgressTick = tickCounter;

        private Session(List<WallBlock> pending, int total) {
            this.pending = pending;
            this.total = total;
        }
    }

    private static final Map<String, Session> SESSIONS = new HashMap<>();

    private static final Map<String, List<UUID>> ASSIGNED = new HashMap<>();

    private static final Map<UUID, String> WORKER_TASK = new HashMap<>();

    private static final Set<String> LINKED_DIMENSIONS = new HashSet<>();

    private static boolean boardDirty;

    private static int tickCounter;

    private WallBuildService() {
    }

    public static boolean isWorking(UUID npcId) {
        return npcId != null && WORKER_TASK.containsKey(npcId);
    }

    public static void reset() {
        SESSIONS.clear();
        releaseAll();
        LINKED_DIMENSIONS.clear();
        tickCounter = 0;
    }

    public static void forget(String key) {
        SESSIONS.remove(key);
        release(key);
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (RegionTaskService.INSTANCE.allEmployees().isEmpty()) {
            return;
        }
        tickCounter++;
        sweepStaleWorkers();
        releaseFinished();
        if (tickCounter % 40 == 0) {
            ensureLinksOnce(server);
        }
        boolean swingDue = tickCounter % SWING_INTERVAL_TICKS == 0;
        List<UUID> idle = idleBuilders();
        int budget = Math.min(24, RegionTaskService.INSTANCE.allEmployees().size() * 2);
        Set<String> changed = new HashSet<>();
        for (WallNode node : WallStore.INSTANCE.allNodes()) {
            if (budget <= 0) {
                break;
            }
            if (WallNode.DONE.equals(node.status())) {
                continue;
            }
            ServerLevel level = WallService.levelOf(server, node.dimension());
            if (level == null) {
                continue;
            }
            if (!moveWorkers(level, node.key(), standsFor(level, node), idle, swingDue)) {
                continue;
            }
            budget -= advanceNode(level, node, budget, changed);
        }
        for (WallSegment segment : WallStore.INSTANCE.allSegments()) {
            if (budget <= 0) {
                break;
            }
            if (WallNode.DONE.equals(segment.status())) {
                continue;
            }
            ServerLevel level = WallService.levelOf(server, segment.dimension());
            if (level == null || !level.isLoaded(segment.a()) || !level.isLoaded(segment.b())) {
                continue;
            }
            if (!moveWorkers(level, segment.key(), standsFor(level, segment), idle, swingDue)) {
                continue;
            }
            budget -= advanceSegment(level, segment, budget, changed);
        }
        for (String dimension : changed) {
            WallService.pushState(server, dimension);
        }
        if (boardDirty && tickCounter % 20 == 0) {
            boardDirty = false;
            com.xy2407.kingdom.server.task.BuilderBoardService.broadcastAll(server);
        }
    }

    private static void sweepStaleWorkers() {
        WORKER_TASK.entrySet().removeIf(entry -> {
            NpcData npc = NpcManager.INSTANCE.getNpc(entry.getKey());
            return !RegionTaskService.INSTANCE.isEmployed(entry.getKey())
                    || npc == null || npc.isDead();
        });
    }

    private static void ensureLinksOnce(MinecraftServer server) {
        Set<String> dimensions = new HashSet<>();
        for (WallNode node : WallStore.INSTANCE.allNodes()) {
            dimensions.add(node.dimension());
        }
        for (String dimension : dimensions) {
            if (!LINKED_DIMENSIONS.add(dimension)) {
                continue;
            }
            if (WallStore.INSTANCE.nodesIn(dimension).size() < 2
                    || !WallStore.INSTANCE.segmentsIn(dimension).isEmpty()) {
                continue;
            }
            ServerLevel level = WallService.levelOf(server, dimension);
            if (level == null) {
                continue;
            }
            int created = WallService.linkNodes(level, dimension);
            if (created > 0) {
                ModMain.LOGGER.info("城墙补连：{} 段（维度 {}）", created, dimension);
                WallService.pushState(server, dimension);
            }
        }
    }

    private static void releaseFinished() {
        for (String key : List.copyOf(ASSIGNED.keySet())) {
            WallNode node = WallStore.INSTANCE.node(key);
            if (node != null) {
                if (WallNode.DONE.equals(node.status())) {
                    SESSIONS.remove(key);
                    release(key);
                }
                continue;
            }
            WallSegment segment = WallStore.INSTANCE.segment(key);
            if (segment == null || WallNode.DONE.equals(segment.status())) {
                SESSIONS.remove(key);
                release(key);
            }
        }
    }

    private static List<UUID> idleBuilders() {
        List<UUID> idle = new ArrayList<>();
        for (RegionTaskService.Employee employee : RegionTaskService.INSTANCE.allEmployees()) {
            if ("待命".equals(RegionTaskService.INSTANCE.statusOf(employee.id()))
                    && !WORKER_TASK.containsKey(employee.id())) {
                idle.add(employee.id());
            }
        }
        return idle;
    }

    private static boolean moveWorkers(ServerLevel level, String taskKey, List<BlockPos> stands,
                                       List<UUID> idle, boolean swingDue) {
        if (stands.isEmpty()) {
            return false;
        }
        List<UUID> assigned = ASSIGNED.computeIfAbsent(taskKey, ignored -> new ArrayList<>());
        java.util.Iterator<UUID> iterator = assigned.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (!RegionTaskService.INSTANCE.isEmployed(id) || npc == null || npc.isDead()) {
                WORKER_TASK.remove(id);
                iterator.remove();
            }
        }
        int cap = Math.max(2, RegionTaskService.INSTANCE.allEmployees().size());
        for (UUID candidate : idle) {
            if (assigned.size() >= cap) {
                break;
            }
            if (assigned.contains(candidate) || WORKER_TASK.containsKey(candidate)) {
                continue;
            }
            assigned.add(candidate);
            WORKER_TASK.put(candidate, taskKey);
        }
        if (assigned.isEmpty()) {
            assigned.clear();
            ASSIGNED.remove(taskKey);
            return false;
        }
        boolean arrived = false;
        for (UUID id : List.copyOf(assigned)) {
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc == null || npc.isDead() || npc.getBrain().isStarving()) {
                continue;
            }
            if (!npc.getBrain().isConstructionControlled()) {
                npc.getBrain().setConstructionControlled(true);
            }
            var entity = NpcManager.INSTANCE.liveEntity(npc);
            if (entity == null) {
                continue;
            }
            BlockPos stand = stands.get(Math.floorMod(id.hashCode(), stands.size()));
            if (entity.tickConstructionPath(stand)) {
                arrived = true;
                if (swingDue) {
                    entity.playSwing();
                }
            }
        }
        return arrived;
    }

    private static void release(String taskKey) {
        List<UUID> assigned = ASSIGNED.remove(taskKey);
        if (assigned == null) {
            return;
        }
        for (UUID id : assigned) {
            WORKER_TASK.remove(id);
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc != null && "待命".equals(RegionTaskService.INSTANCE.statusOf(id))) {
                npc.getBrain().setConstructionControlled(false);
            }
        }
    }

    private static void releaseAll() {
        for (String key : List.copyOf(ASSIGNED.keySet())) {
            release(key);
        }
        WORKER_TASK.clear();
    }

    private static List<BlockPos> standsFor(ServerLevel level, WallNode node) {
        List<BlockPos> stands = new ArrayList<>();
        int x = node.pos().getX();
        int z = node.pos().getZ();
        int[][] offsets = {{-2, -2}, {6, -2}, {-2, 6}, {6, 6}};
        for (int[] offset : offsets) {
            stands.add(standAt(level, x + offset[0], z + offset[1], node.pos().getY()));
        }
        return stands;
    }

    private static List<BlockPos> standsFor(ServerLevel level, WallSegment segment) {
        List<BlockPos> stands = new ArrayList<>();
        int dirX = Integer.compare(segment.b().getX(), segment.a().getX());
        int dirZ = Integer.compare(segment.b().getZ(), segment.a().getZ());
        int perpX = -dirZ;
        int perpZ = dirX;
        if (perpX == 0 && perpZ == 0) {
            perpX = 1;
        }
        BlockPos center = centerOf(segment);
        if (perpX * (center.getX() - segment.a().getX())
                + perpZ * (center.getZ() - segment.a().getZ()) < 0) {
            perpX = -perpX;
            perpZ = -perpZ;
        }
        for (double t : new double[] {0.2D, 0.5D, 0.8D}) {
            int x = (int) Math.round(segment.a().getX()
                    + (segment.b().getX() - segment.a().getX()) * t);
            int z = (int) Math.round(segment.a().getZ()
                    + (segment.b().getZ() - segment.a().getZ()) * t);
            stands.add(freeStand(level, x + perpX * 3, z + perpZ * 3,
                    segment.a().getY(), perpX, perpZ));
        }
        return stands;
    }

    private static BlockPos freeStand(ServerLevel level, int x, int z, int refY,
                                      int stepX, int stepZ) {
        for (int offset = 0; offset <= 6; offset++) {
            int px = x + stepX * offset;
            int pz = z + stepZ * offset;
            int surface = WorldSurfaceSampler.walkableSurfaceY(level, px, pz, refY);
            if (surface == WorldSurfaceSampler.NO_WALKABLE) {
                surface = WorldSurfaceSampler.walkableSurfaceY(level, px, pz,
                        WorldSurfaceSampler.NO_WALKABLE);
            }
            if (surface == WorldSurfaceSampler.NO_WALKABLE) {
                continue;
            }
            BlockPos stand = new BlockPos(px, surface + 1, pz);
            if (level.getBlockState(stand).isAir()
                    && level.getBlockState(stand.above()).isAir()) {
                return stand;
            }
        }
        return standAt(level, x, z, refY);
    }

    private static BlockPos standAt(ServerLevel level, int x, int z, int refY) {
        int surfaceY = WorldSurfaceSampler.walkableSurfaceY(level, x, z, refY);
        if (surfaceY == WorldSurfaceSampler.NO_WALKABLE) {
            surfaceY = WorldSurfaceSampler.walkableSurfaceY(level, x, z,
                    WorldSurfaceSampler.NO_WALKABLE);
        }
        if (surfaceY == WorldSurfaceSampler.NO_WALKABLE) {
            return new BlockPos(x, refY + 1, z);
        }
        return new BlockPos(x, surfaceY + 1, z);
    }

    private static int advanceNode(ServerLevel level, WallNode node, int budget, Set<String> changed) {
        Session session = SESSIONS.get(node.key());
        if (session == null) {
            List<WallBlock> blocks = WallStore.INSTANCE.blocksOf(node.key());
            if (blocks.isEmpty()) {
                blocks = WallGenerator.nodeBlocks(level, node);
            }
            if (blocks.isEmpty()) {
                return 0;
            }
            int from = Math.min(Math.max(0, node.progress()), blocks.size());
            session = new Session(new ArrayList<>(blocks.subList(from, blocks.size())), blocks.size());
            SESSIONS.put(node.key(), session);
            WallStore.INSTANCE.updateNode(node.advance(from, WallNode.BUILDING));
        }
        return place(level, node.key(), true, node.dimension(), session, budget, changed);
    }

    private static int advanceSegment(ServerLevel level, WallSegment segment, int budget,
                                      Set<String> changed) {
        Session session = SESSIONS.get(segment.key());
        if (session == null) {
            List<WallBlock> blocks = WallStore.INSTANCE.blocksOf(segment.key());
            if (blocks.isEmpty()) {
                blocks = WallGenerator.segmentBlocks(level, segment, centerOf(segment));
            } else {
                // 老数据自愈：任务开建前按当前生成器重算一次，旧版（斜向墙侵入节点足迹）
                // 落盘的方块直接替换掉，避免把存量的错误方块建进世界
                List<WallBlock> regenerated =
                        WallGenerator.segmentBlocks(level, segment, centerOf(segment));
                if (regenerated.isEmpty()) {
                    // 全段都被节点足迹覆盖（两节点紧贴）：没有墙可建，直接完工
                    WallStore.INSTANCE.updateSegment(segment.advance(segment.total(),
                            WallNode.DONE));
                    return 0;
                }
                if (!sameBlocks(blocks, regenerated)) {
                    blocks = regenerated;
                    WallStore.INSTANCE.recordBlocks(segment.key(), segment.dimension(), blocks);
                }
            }
            if (blocks.isEmpty()) {
                return 0;
            }
            int from = Math.min(Math.max(0, segment.progress()), blocks.size());
            session = new Session(new ArrayList<>(blocks.subList(from, blocks.size())), blocks.size());
            SESSIONS.put(segment.key(), session);
            WallStore.INSTANCE.updateSegment(segment.advance(from, WallNode.BUILDING));
        }
        return place(level, segment.key(), false, segment.dimension(), session, budget, changed);
    }

    private static boolean sameBlocks(List<WallBlock> stored, List<WallBlock> generated) {
        if (stored.size() != generated.size()) {
            return false;
        }
        java.util.Set<Long> keys = new java.util.HashSet<>(stored.size());
        for (WallBlock block : stored) {
            keys.add(BlockPos.asLong(block.x(), block.y(), block.z()));
        }
        for (WallBlock block : generated) {
            if (!keys.remove(BlockPos.asLong(block.x(), block.y(), block.z()))) {
                return false;
            }
        }
        return keys.isEmpty();
    }

    private static int place(ServerLevel level, String key, boolean node, String dimension,
                             Session session, int budget, Set<String> changed) {
        List<WallBlock> pending = session.pending;
        List<WallBlock> touched = new ArrayList<>();
        Set<Long> occupied = workerCells(key);
        int used = 0;
        java.util.Iterator<WallBlock> iterator = pending.iterator();
        while (iterator.hasNext() && used < budget) {
            WallBlock block = iterator.next();
            if (occupied.contains(block.pos().asLong())) {
                continue;
            }
            level.setBlock(block.pos(), Block.stateById(block.stateId()), PLACE_FLAGS);
            iterator.remove();
            touched.add(block);
            used++;
        }
        WallService.markDirtyBlocks(level, touched);
        if (used > 0) {
            session.lastProgressTick = tickCounter;
        } else if (tickCounter - session.lastProgressTick > STALL_RELEASE_TICKS) {
            SESSIONS.remove(key);
            release(key);
            return 0;
        }
        int done = session.total - pending.size();
        if (pending.isEmpty()) {
            if (node) {
                WallNode current = WallStore.INSTANCE.node(key);
                if (current != null) {
                    WallStore.INSTANCE.updateNode(current.advance(session.total, WallNode.DONE));
                }
            } else {
                WallSegment current = WallStore.INSTANCE.segment(key);
                if (current != null) {
                    WallStore.INSTANCE.updateSegment(current.advance(session.total, WallNode.DONE));
                }
            }
            SESSIONS.remove(key);
            release(key);
            boardDirty = true;
            changed.add(dimension);
        } else if (used > 0 || tickCounter % SAVE_INTERVAL_TICKS == 0) {
            if (node) {
                WallNode current = WallStore.INSTANCE.node(key);
                if (current != null) {
                    WallStore.INSTANCE.updateNode(current.advance(done, WallNode.BUILDING));
                }
            } else {
                WallSegment current = WallStore.INSTANCE.segment(key);
                if (current != null) {
                    WallStore.INSTANCE.updateSegment(current.advance(done, WallNode.BUILDING));
                }
            }
            changed.add(dimension);
        }
        return used;
    }

    private static Set<Long> workerCells(String taskKey) {
        Set<Long> cells = new HashSet<>();
        for (UUID id : ASSIGNED.getOrDefault(taskKey, List.of())) {
            NpcData npc = NpcManager.INSTANCE.getNpc(id);
            if (npc == null) {
                continue;
            }
            var entity = NpcManager.INSTANCE.liveEntity(npc);
            if (entity == null) {
                continue;
            }
            BlockPos feet = entity.blockPosition();
            cells.add(feet.asLong());
            cells.add(feet.above().asLong());
        }
        return cells;
    }

    private static BlockPos centerOf(WallSegment segment) {
        String nodeKey = WallSchema.nodeKey(segment.dimension(), segment.a().getX(),
                segment.a().getY(), segment.a().getZ());
        WallNode anchor = WallStore.INSTANCE.node(nodeKey);
        if (anchor != null) {
            Territory territory = TerritoryRegistry.INSTANCE.byId(anchor.territoryId());
            if (territory != null) {
                return new BlockPos(territory.centerX(), territory.centerY(), territory.centerZ());
            }
        }
        return segment.a();
    }
}
