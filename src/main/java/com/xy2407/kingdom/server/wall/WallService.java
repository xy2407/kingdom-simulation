package com.xy2407.kingdom.server.wall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.wall.WallPickResultPacket;
import com.xy2407.kingdom.common.network.wall.WallStatePacket;
import com.xy2407.kingdom.common.network.wall.WallSubmitPacket;
import com.xy2407.kingdom.common.persistence.WallSchema;
import com.xy2407.kingdom.common.wall.WallBlock;
import com.xy2407.kingdom.common.wall.WallKind;
import com.xy2407.kingdom.common.wall.WallNode;
import com.xy2407.kingdom.common.wall.WallSegment;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.path.BuildingRoadAccess;
import com.xy2407.kingdom.server.path.RoadGraphService;
import com.xy2407.kingdom.server.path.UnitSnapshotStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 城墙服务端：节点提交、自动连线、删除级联与状态下发
 */
public final class WallService {

    private static final int MAX_LINK_DISTANCE = 128;

    private static final int MAX_LINKS = 2;

    private static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private WallService() {
    }

    public static void submit(ServerPlayer player, List<WallSubmitPacket.Entry> entries) {
        if (player == null || entries == null || entries.isEmpty()) {
            return;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        if (territory == null) {
            notice(player, "你没有领地，无法建造城墙");
            return;
        }
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        int created = 0;
        int skipped = 0;
        for (WallSubmitPacket.Entry entry : entries) {
            String key = WallSchema.nodeKey(dimension, entry.x(), entry.y(), entry.z());
            if (WallStore.INSTANCE.node(key) != null) {
                skipped++;
                continue;
            }
            WallKind kind = entry.kind() == null ? WallKind.TOWER : entry.kind();
            WallNode node = new WallNode(key, kind, dimension,
                    new BlockPos(entry.x(), entry.y(), entry.z()), entry.rotation(),
                    WallNode.PLANNED, 0, 0, territory.id(), player.getUUID().toString());
            List<WallBlock> planned = WallGenerator.nodeBlocks(level, node);
            if (!planned.isEmpty()) {
                WallStore.INSTANCE.recordBlocks(key, dimension, planned);
                node = node.withTotal(planned.size());
            }
            WallStore.INSTANCE.recordNode(node);
            created++;
        }
        if (created == 0) {
            notice(player, "没有可提交的城墙节点（位置重复或投影未固定）");
            return;
        }
        int links = linkNodes(level, dimension);
        int totalNodes = WallStore.INSTANCE.nodesIn(dimension).size();
        notice(player, "已提交 " + created + " 个节点、" + links + " 段城墙"
                + (skipped > 0 ? "（跳过 " + skipped + " 个）" : ""));
        if (links == 0 && totalNodes >= 2) {
            notice(player, "未生成城墙：节点间距超过 " + MAX_LINK_DISTANCE
                    + " 格，或两点之间已有第三个节点");
        }
        sendState(player);
        pushState(level.getServer(), dimension);
    }

    static int linkNodes(ServerLevel level, String dimension) {
        List<WallNode> all = WallStore.INSTANCE.nodesIn(dimension);
        record Pair(WallNode a, WallNode b, double distance) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                WallNode a = all.get(i);
                WallNode b = all.get(j);
                double dx = b.pos().getX() - a.pos().getX();
                double dz = b.pos().getZ() - a.pos().getZ();
                double distance = dx * dx + dz * dz;
                if (distance <= (double) MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) {
                    pairs.add(new Pair(a, b, distance));
                }
            }
        }
        pairs.sort((left, right) -> Double.compare(left.distance(), right.distance()));
        Map<String, Integer> degrees = new HashMap<>();
        for (WallNode node : all) {
            degrees.put(node.key(), connectionsOf(node.key()));
        }
        int created = 0;
        for (Pair pair : pairs) {
            WallNode a = pair.a();
            WallNode b = pair.b();
            if (degrees.getOrDefault(a.key(), 0) >= MAX_LINKS
                    || degrees.getOrDefault(b.key(), 0) >= MAX_LINKS) {
                continue;
            }
            String key = WallSchema.segmentKey(dimension, a.key(), b.key());
            if (WallStore.INSTANCE.segment(key) != null) {
                continue;
            }
            if (passesNode(a, b, all)) {
                continue;
            }
            WallSegment segment = new WallSegment(key, dimension, a.pos(), b.pos(),
                    WallNode.PLANNED, 0,
                    Math.max(2, WallGenerator.columnCount(a.pos(), b.pos())));
            List<WallBlock> planned = List.of();
            try {
                preload(level, a.pos(), b.pos());
                planned = WallGenerator.segmentBlocks(level, segment, centerOfNode(a));
            } catch (RuntimeException failure) {
                ModMain.LOGGER.error("城墙连段生成失败：{} -> {}", a.key(), b.key(), failure);
            }
            if (!planned.isEmpty()) {
                WallStore.INSTANCE.recordBlocks(key, dimension, planned);
                segment = segment.withTotal(planned.size());
            } else {
                // 全部墙柱都落在节点足迹里（两节点紧贴）时没有墙可建，段不创建，
                // 避免留下一个永远无方块的空任务
                continue;
            }
            WallStore.INSTANCE.recordSegment(segment);
            degrees.merge(a.key(), 1, Integer::sum);
            degrees.merge(b.key(), 1, Integer::sum);
            created++;
        }
        return created;
    }

    private static BlockPos centerOfNode(WallNode node) {
        Territory territory = TerritoryRegistry.INSTANCE.byId(node.territoryId());
        if (territory != null) {
            return new BlockPos(territory.centerX(), territory.centerY(), territory.centerZ());
        }
        return node.pos();
    }

    private static boolean passesNode(WallNode a, WallNode b, List<WallNode> all) {
        double ax = a.pos().getX();
        double az = a.pos().getZ();
        double bx = b.pos().getX();
        double bz = b.pos().getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr < 1.0D) {
            return true;
        }
        for (WallNode other : all) {
            if (other.key().equals(a.key()) || other.key().equals(b.key())) {
                continue;
            }
            double t = ((other.pos().getX() - ax) * dx + (other.pos().getZ() - az) * dz) / lengthSqr;
            if (t <= 0.0D || t >= 1.0D) {
                continue;
            }
            double px = ax + dx * t;
            double pz = az + dz * t;
            double ddx = other.pos().getX() - px;
            double ddz = other.pos().getZ() - pz;
            if (ddx * ddx + ddz * ddz <= 16.0D) {
                return true;
            }
        }
        return false;
    }

    private static void preload(ServerLevel level, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        for (int index = 0; index <= Math.max(1, steps); index += 4) {
            double t = steps <= 0 ? 0.0D : (double) index / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (chunkX == lastChunkX && chunkZ == lastChunkZ) {
                continue;
            }
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            level.getChunk(chunkX, chunkZ);
        }
    }

    private static int connectionsOf(String nodeKey) {
        return WallStore.INSTANCE.segmentsTouching(nodeKey).size();
    }

    public static void delete(ServerPlayer player, String key, boolean node) {
        if (player == null || key == null || key.isBlank()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        if (node) {
            WallNode target = WallStore.INSTANCE.node(key);
            if (target == null || !canEdit(player, territory, target.owner(), target.territoryId())) {
                notice(player, "无法删除：节点不存在或不属于你");
                return;
            }
            for (WallSegment segment : WallStore.INSTANCE.segmentsTouching(key)) {
                removeSegment(level, segment);
            }
            if (WallStore.INSTANCE.blocksOf(key).isEmpty()) {
                clearGenerated(level, WallGenerator.nodeBlocks(level, target));
            }
            clearBlocks(level, key);
            WallStore.INSTANCE.removeNode(key);
            notice(player, "已拆除节点");
        } else {
            WallSegment segment = WallStore.INSTANCE.segment(key);
            if (segment == null) {
                return;
            }
            String nodeKey = WallSchema.nodeKey(dimension, segment.a().getX(),
                    segment.a().getY(), segment.a().getZ());
            WallNode anchor = WallStore.INSTANCE.node(nodeKey);
            if (anchor != null && !canEdit(player, territory, anchor.owner(), anchor.territoryId())) {
                notice(player, "无法删除：墙段不属于你");
                return;
            }
            removeSegment(level, segment);
            notice(player, "已拆除墙段");
        }
        sendState(player);
        pushState(level.getServer(), dimension);
    }

    private static boolean canEdit(ServerPlayer player, Territory territory,
                                   String owner, String territoryId) {
        if (owner != null && owner.equals(player.getUUID().toString())) {
            return true;
        }
        return territory != null && territory.id().equals(territoryId);
    }

    private static void removeSegment(ServerLevel level, WallSegment segment) {
        if (WallStore.INSTANCE.blocksOf(segment.key()).isEmpty()) {
            clearGenerated(level, WallGenerator.segmentBlocks(level, segment, centerOf(segment)));
        }
        clearBlocks(level, segment.key());
        WallStore.INSTANCE.removeSegment(segment.key());
        WallStore.INSTANCE.removeBlocks(segment.key());
        WallBuildService.forget(segment.key());
        markDirtyAround(level, segment);
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

    private static void clearGenerated(ServerLevel level, List<WallBlock> generated) {
        int removed = 0;
        for (WallBlock block : generated) {
            BlockPos pos = block.pos();
            BlockState current = level.getBlockState(pos);
            if (Block.getId(current) == block.stateId()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                removed++;
            }
        }
        if (removed > 0) {
            ModMain.LOGGER.info("城墙拆除：清理未完工方块 {} 个", removed);
        }
    }

    private static void clearBlocks(ServerLevel level, String taskKey) {
        List<WallBlock> placed = WallStore.INSTANCE.blocksOf(taskKey);
        if (placed.isEmpty()) {
            WallStore.INSTANCE.removeBlocks(taskKey);
            return;
        }
        for (WallBlock block : placed) {
            BlockPos pos = block.pos();
            BlockState current = level.getBlockState(pos);
            if (Block.getId(current) == block.stateId()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            }
        }
        markDirtyBlocks(level, placed);
    }

    private static void markDirtyAround(ServerLevel level, WallSegment segment) {
        List<WallBlock> around = new ArrayList<>();
        BlockPos a = segment.a();
        BlockPos b = segment.b();
        around.add(new WallBlock(a.getX(), a.getY(), a.getZ(), 0));
        around.add(new WallBlock(b.getX(), b.getY(), b.getZ(), 0));
        markDirtyBlocks(level, around);
    }

    public static void markDirtyBlocks(ServerLevel level, List<WallBlock> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        Set<Long> visited = new java.util.HashSet<>();
        for (WallBlock block : blocks) {
            int chunkX = block.x() >> 4;
            int chunkZ = block.z() >> 4;
            int layer = block.y() >> 4;
            long key = (((long) chunkX) << 42) ^ (((long) layer) << 21) ^ (chunkZ & 0x1FFFFFL);
            if (!visited.add(key)) {
                continue;
            }
            UnitSnapshotStore.INSTANCE.markDirty(dimension, chunkX, layer, chunkZ,
                    level.getGameTime());
        }
        RoadGraphService.markDirty(dimension);
        BuildingRoadAccess.markRoadDirty();
    }

    public static UUID taskIdOf(String key) {
        return UUID.nameUUIDFromBytes((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean applyBoardAction(ServerPlayer player, UUID taskId, int action) {
        if (taskId == null) {
            return false;
        }
        for (WallNode node : WallStore.INSTANCE.allNodes()) {
            if (taskIdOf(node.key()).equals(taskId)) {
                if (action == com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket
                        .ACTION_CANCEL) {
                    delete(player, node.key(), true);
                }
                return true;
            }
        }
        for (WallSegment segment : WallStore.INSTANCE.allSegments()) {
            if (taskIdOf(segment.key()).equals(taskId)) {
                if (action == com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket
                        .ACTION_CANCEL) {
                    delete(player, segment.key(), false);
                }
                return true;
            }
        }
        return false;
    }

    public static void sendState(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String dimension = player.serverLevel().dimension().location().toString();
        PacketDistributor.sendToPlayer(player, stateOf(dimension));
    }

    /**
     * RTS 点击方块：按 wall_blocks 逐方块反查归属任务，回全量方块给客户端做逐方块选中
     */
    public static void pick(ServerPlayer player, int x, int y, int z) {
        if (player == null) {
            return;
        }
        String dimension = player.serverLevel().dimension().location().toString();
        String key = WallStore.INSTANCE.taskAt(dimension, x, y, z);
        if (key == null) {
            PacketDistributor.sendToPlayer(player, new WallPickResultPacket(null, false, List.of()));
            return;
        }
        boolean node = WallStore.INSTANCE.node(key) != null;
        List<Long> blocks = new ArrayList<>();
        for (WallBlock block : WallStore.INSTANCE.taskBlocks(key)) {
            blocks.add(BlockPos.asLong(block.x(), block.y(), block.z()));
        }
        PacketDistributor.sendToPlayer(player, new WallPickResultPacket(key, node, blocks));
    }

    public static void pushState(MinecraftServer server, String dimension) {
        if (server == null) {
            return;
        }
        WallStatePacket packet = stateOf(dimension);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension().location().toString().equals(dimension)) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    private static WallStatePacket stateOf(String dimension) {
        List<WallStatePacket.NodeView> nodes = new ArrayList<>();
        for (WallNode node : WallStore.INSTANCE.nodesIn(dimension)) {
            nodes.add(new WallStatePacket.NodeView(node.key(), node.kind(),
                    node.pos().getX(), node.pos().getY(), node.pos().getZ(),
                    node.rotation(), node.status(), node.progress(), node.total()));
        }
        List<WallStatePacket.SegmentView> segments = new ArrayList<>();
        for (WallSegment segment : WallStore.INSTANCE.segmentsIn(dimension)) {
            segments.add(new WallStatePacket.SegmentView(segment.key(),
                    segment.a().getX(), segment.a().getY(), segment.a().getZ(),
                    segment.b().getX(), segment.b().getY(), segment.b().getZ(),
                    segment.status(), segment.progress(), segment.total()));
        }
        return new WallStatePacket(nodes, segments);
    }

    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (server == null || dimension == null) {
            return null;
        }
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension)));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void notice(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), false);
    }
}
