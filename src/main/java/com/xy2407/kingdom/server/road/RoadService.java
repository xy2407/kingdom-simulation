package com.xy2407.kingdom.server.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.common.path.RtsSharedPath;
import com.xy2407.kingdom.common.network.road.RoadLoadedChunksPacket;
import com.xy2407.kingdom.common.road.RoadGeometry;
import com.xy2407.kingdom.common.road.RoadPolyline;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.mapoverlay.MapOverlayCollector;
import com.xy2407.kingdom.server.path.BuildingRoadAccess;
import com.xy2407.kingdom.server.path.RoadFieldCache;
import com.xy2407.kingdom.server.path.RoadGraphService;

import com.xy2407.kingdom.server.path.RoadRouteService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 道路的服务端处理：领地道路提交校验铺设、世界道路折线重算入队、已加载区块下发与道路传送。
 */
public final class RoadService {

    static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static final long TICKS_PER_DAY = 24000L;

    private RoadService() {
    }

    public static void submitTerritory(ServerPlayer player, long[] cells, boolean clear) {
        if (player == null || cells == null || cells.length == 0) {
            return;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        if (territory == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(territory.dimension())) {
            return;
        }
        if (clear) {
            int removed = 0;
            for (long raw : cells) {
                BlockPos column = BlockPos.of(raw);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = trustedSurface(level, column);
                if (surface == null || !RoadStore.INSTANCE.contains(dimension, surface)) {
                    continue;
                }
                RoadStore.INSTANCE.removeCell(dimension, surface);
                removed++;
            }
            ModMain.LOGGER.info("领地道路清除：提交 {} 格，实际删除 {} 格（{}）",
                    cells.length, removed, player.getGameProfile().getName());
            if (removed > 0) {
                RoadGraphService.markDirty(dimension);
                BuildingRoadAccess.markRoadDirty();
                RoadFieldCache.reset();
                RoadRouteService.clearNoAccess();
                RtsSharedPath.clearAll();
                MapOverlayCollector.pushTo(player);
            }
            sendLoadedChunks(player);
            return;
        }
        int placed = 0;
        for (long raw : cells) {
            BlockPos column = BlockPos.of(raw);
            if (!territory.containsBlock(column.getX(), column.getZ()) || !level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = trustedSurface(level, column);
            if (surface == null) {
                continue;
            }
            BlockState current = level.getBlockState(surface);
            RoadStore.INSTANCE.record(dimension, surface, territory.id(),
                    player.getUUID().toString(), level.getDayTime() / TICKS_PER_DAY);
            BlockState material = roadMaterial(level, dimension, surface);
            if (!current.is(material.getBlock())) {
                if (level.getBlockEntity(surface) != null) {
                    level.removeBlockEntity(surface);
                }
                level.setBlock(surface, material, PLACE_FLAGS);
                placed++;
            }
            repaveNeighbors(level, dimension, surface);
        }
        ModMain.LOGGER.info("领地道路：提交 {} 格，实际铺设 {} 格（{}）",
                cells.length, placed, player.getGameProfile().getName());
        if (placed > 0) {
            RoadGraphService.markDirty(dimension);
            BuildingRoadAccess.markRoadDirty();
            RoadFieldCache.reset();
            RoadRouteService.clearNoAccess();
            RtsSharedPath.clearAll();
            MapOverlayCollector.pushTo(player);
        }
        sendLoadedChunks(player);
    }

    // 材质杂化：四正向缺邻 = 边缘 → 石砖；四向齐全 = 内部 → 圆石/石头/沙砾按格稳定哈希取（成斑块）
    private static BlockState roadMaterial(ServerLevel level, String dimension, BlockPos surface) {
        boolean edge = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!RoadStore.INSTANCE.contains(dimension, surface.relative(direction))) {
                edge = true;
                break;
            }
        }
        if (edge) {
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        int hash = (surface.getX() * 341 + surface.getZ() * 137 + surface.getY() * 7) & 0x7FFFFFFF;
        return switch (hash % 3) {
            case 0 -> Blocks.COBBLESTONE.defaultBlockState();
            case 1 -> Blocks.STONE.defaultBlockState();
            default -> Blocks.GRAVEL.defaultBlockState();
        };
    }

    // 邻居重算：新格接入后原边缘格转为内部，接缝向一条完整路收敛；材质不落库
    private static void repaveNeighbors(ServerLevel level, String dimension, BlockPos surface) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = surface.relative(direction);
            if (!RoadStore.INSTANCE.contains(dimension, neighbor)) {
                continue;
            }
            BlockState material = roadMaterial(level, dimension, neighbor);
            if (!level.getBlockState(neighbor).is(material.getBlock())) {
                level.setBlock(neighbor, material, PLACE_FLAGS);
            }
        }
    }

    // D1 已加载区块下发：以玩家为中心扫半径 24 区块（768 格见方，共 2401 格 < 4096 上限，无需按距离排序）
    public static void sendLoadedChunks(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ChunkPos center = player.chunkPosition();
        int radius = 24;
        long[] keys = new long[(radius * 2 + 1) * (radius * 2 + 1)];
        int count = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    keys[count++] = ChunkPos.asLong(chunkX, chunkZ);
                }
            }
        }
        long[] trimmed = new long[count];
        System.arraycopy(keys, 0, trimmed, 0, count);
        PacketDistributor.sendToPlayer(player, new RoadLoadedChunksPacket(trimmed));
    }

    // 服务端权威重算（W10/补充段1）：不信任客户端的 rooted 与裁剪结果，只收折线，连通性/领地裁剪/覆盖格都按服务端数据重算
    public static void submitWorld(ServerPlayer player, List<RoadPolyline> lines) {
        if (player == null || lines == null || lines.isEmpty()) {
            return;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        if (territory == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(territory.dimension())) {
            return;
        }
        List<RoadPolyline> rooted = rootedLines(lines, territory.chunks());
        if (rooted.isEmpty()) {
            notice(player, "没有与领地连通的线条，本次提交全部丢弃");
            return;
        }
        List<RoadPolyline> clipped = RoadGeometry.clipToOutsideTerritory(rooted, territory.chunks());
        var cellsByChunk = RoadGeometry.cellsByChunk(clipped);
        if (cellsByChunk.isEmpty()) {
            notice(player, "裁掉领地内部分后没有剩余道路格，本次提交丢弃");
            return;
        }
        RoadPavingQueue.enqueue(player.getUUID(), dimension, cellsByChunk);
        notice(player, "世界道路已入队：" + cellsByChunk.size()
                + " 个区块，将按每 40 tick 10 区块分批铺设，铺完可在任意地图上看到");
    }

    private static List<RoadPolyline> rootedLines(List<RoadPolyline> lines, Set<Long> ownChunks) {
        boolean[] rooted = RoadGeometry.computeRooted(lines, ownChunks);
        List<RoadPolyline> result = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (rooted[index]) {
                result.add(lines.get(index));
            }
        }
        return result;
    }

    private static void notice(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), false);
    }

    // W15 + D3：只允许传送到自家领地内的道路区块、或自己提交过的世界道路区块；落到区块中心的地表并保留朝向
    public static void teleportToRoad(ServerPlayer player, int chunkX, int chunkZ) {
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (!RoadStore.INSTANCE.hasRoadChunk(dimension, chunkX, chunkZ)) {
            return;
        }
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        boolean ownChunk = territory != null && territory.dimension().equals(dimension)
                && territory.containsChunk(chunkX, chunkZ);
        String owner = RoadStore.INSTANCE.roadOwner(dimension, chunkX, chunkZ);
        if (!ownChunk && !player.getUUID().toString().equals(owner)) {
            notice(player, "只能传送到自家领地内的道路、或自己铺设的世界道路");
            return;
        }
        int centerY = WorldSurfaceSampler.walkableSurfaceY(level,
                (chunkX << 4) + 8, (chunkZ << 4) + 8, WorldSurfaceSampler.NO_WALKABLE);
        if (centerY == WorldSurfaceSampler.NO_WALKABLE) {
            notice(player, "该区块中心找不到可行走的地面，传送取消");
            return;
        }
        BlockPos center = new BlockPos((chunkX << 4) + 8, centerY, (chunkZ << 4) + 8);
        player.teleportTo(level, center.getX() + 0.5D, center.getY() + 1.0D, center.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        ModMain.LOGGER.info("道路传送：{} → 区块 {},{}",
                player.getGameProfile().getName(), chunkX, chunkZ);
    }

    // 客户端随格发的 y 已经过"支撑 + 相邻±2"约束，服务端校验支撑后照用（保持画布上看到的即所铺）；
    // 校验不过（作弊/漂移）才退回无参考的可行走采样
    private static BlockPos trustedSurface(ServerLevel level, BlockPos column) {
        int x = column.getX();
        int y = column.getY();
        int z = column.getZ();
        if (isWalkable(level, x, y, z)) {
            return new BlockPos(x, y, z);
        }
        int fallback = WorldSurfaceSampler.walkableSurfaceY(level, x, z, WorldSurfaceSampler.NO_WALKABLE);
        return fallback == WorldSurfaceSampler.NO_WALKABLE ? null : new BlockPos(x, fallback, z);
    }

    // 与 WorldSurfaceSampler#walkableSurfaceY 的候选判定保持一致（D1：复用快照谓词，净空 2），
    // 否则客户端过了服务端不过会退回 fallback，出现"画布所见非所铺"
    private static boolean isWalkable(ServerLevel level, int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y + 2 > level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(x, y, z);
        if (!ChunkSnapshot.isWalkableFloor(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 1, z);
        if (!ChunkSnapshot.isPassable(level.getBlockState(cursor))) {
            return false;
        }
        cursor.set(x, y + 2, z);
        return ChunkSnapshot.isPassable(level.getBlockState(cursor));
    }

    static BlockPos surfacePos(ServerLevel level, int x, int z) {
        int floor = level.getMinBuildHeight();
        int top = Math.min(level.getMaxBuildHeight() - 1,
                level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top; y > floor; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && !state.getCollisionShape(level, cursor).isEmpty()) {
                return cursor.immutable();
            }
        }
        return new BlockPos(x, top, z);
    }
}
