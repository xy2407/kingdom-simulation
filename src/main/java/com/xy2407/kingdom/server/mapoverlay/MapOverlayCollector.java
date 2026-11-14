package com.xy2407.kingdom.server.mapoverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.TerritoryTier;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayDataPacket;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayHooks;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayRequestPacket;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsService;
import com.xy2407.kingdom.server.road.RoadStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 采集地图叠层数据：领地列表（含人口、等级、领主名与阵营）与建筑列表；
 * 建筑只发玩家自己领地内的那些，其余领地内的建筑一律不下发。
 *
 * <p>推送策略：{@link #pushAll()} 只做标记，由服务端 tick 每 {@link #PUSH_INTERVAL_TICKS} 拍
 * 合并成一次发送；且只发给「最近请求过叠层」的玩家（等于地图正开着），
 * 发送范围沿用该玩家自己请求的视口，避免给没看地图的人白算白传。</p>
 */
public final class MapOverlayCollector {

    private static final int BUILDING_LIMIT = 512;

    private static final int ROAD_CHUNK_LIMIT = 128;

    /** 地图关闭后仍按「开着」处理的宽限期（tick）：客户端开着地图最多 30 秒会重发一次请求，
     *  这里取 40 秒，保证开着地图的人始终能收到实时推送，关掉之后最多再多发约 40 秒。 */
    private static final int VIEW_TTL_TICKS = 800;

    /** 推送合并窗口（tick）：同一窗口内的多次变更只发一次。 */
    private static final int PUSH_INTERVAL_TICKS = 5;

    /** 按视口裁剪领地时额外放宽的方块数，避免贴边漏掉。 */
    private static final int CULL_MARGIN = 256;

    private static MinecraftServer server;

    private static boolean dirty;

    private static long tickCounter;

    private static long lastFlushTick = -PUSH_INTERVAL_TICKS;

    private static long revision;

    /** 玩家最后一次请求的视口：用来决定推送范围和「地图是否开着」。 */
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    private record View(int centerX, int centerZ, int radius, boolean territoryScope, long tick) {
    }

    private MapOverlayCollector() {
    }

    public static void install(MinecraftServer minecraftServer) {
        server = minecraftServer;
        VIEWS.clear();
        dirty = false;
        tickCounter = 0;
        lastFlushTick = -PUSH_INTERVAL_TICKS;
        revision = 0;
        MapOverlayHooks.requestHandler = MapOverlayCollector::onRequest;
    }

    /** 数据变了：只打标记，真正发送由 {@link #tick} 合并成一趟。 */
    public static void pushAll() {
        dirty = true;
    }

    /** 服务端每 tick 调用：合并窗口到期才把脏数据推给「地图开着」的玩家。 */
    public static void tick(MinecraftServer minecraftServer) {
        if (minecraftServer == null) {
            return;
        }
        server = minecraftServer;
        tickCounter++;
        if (VIEWS.isEmpty()) {
            dirty = false;
            return;
        }
        if (tickCounter % 100L == 0L) {
            VIEWS.entrySet().removeIf(entry -> tickCounter - entry.getValue().tick() > VIEW_TTL_TICKS);
        }
        if (!dirty || tickCounter - lastFlushTick < PUSH_INTERVAL_TICKS) {
            return;
        }
        dirty = false;
        lastFlushTick = tickCounter;
        for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
            View view = freshView(player);
            if (view != null) {
                PacketDistributor.sendToPlayer(player, collect(player, view));
            }
        }
    }

    private static View freshView(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        View view = VIEWS.get(player.getUUID());
        if (view == null || tickCounter - view.tick() > VIEW_TTL_TICKS) {
            return null;
        }
        return view;
    }

    private static void onRequest(ServerPlayer player, MapOverlayRequestPacket packet) {
        boolean territoryScope = packet.radius() <= 0;
        int radius = territoryScope ? 16 : packet.radius();
        View view = new View(packet.centerX(), packet.centerZ(), radius, territoryScope, tickCounter);
        VIEWS.put(player.getUUID(), view);
        PacketDistributor.sendToPlayer(player, collect(player, view));
    }

    /** 道路等私有数据变动后手动补发：只发给地图正开着的那位，用他自己请求的视口。 */
    public static void pushTo(ServerPlayer player) {
        if (server == null) {
            return;
        }
        View view = freshView(player);
        if (view != null) {
            PacketDistributor.sendToPlayer(player, collect(player, view));
        }
    }

    public static MapOverlayDataPacket collect(ServerPlayer player, int centerX, int centerZ, int radius) {
        return collect(player, new View(centerX, centerZ, radius, false, tickCounter));
    }

    private static MapOverlayDataPacket collect(ServerPlayer player, View view) {
        return collect(player, view.centerX(), view.centerZ(), view.radius(), view.territoryScope());
    }

    public static MapOverlayDataPacket collect(ServerPlayer player, int centerX, int centerZ, int radius,
                                               boolean territoryScope) {
        String dimension = player.level().dimension().location().toString();
        Territory own = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());

        int radiusValue = Math.max(16, radius);
        int minX = centerX - radiusValue;
        int maxX = centerX + radiusValue;
        int minZ = centerZ - radiusValue;
        int maxZ = centerZ + radiusValue;

        // 视口对应的区块范围（含放宽量）：领地只发和它有交集的，其余整块不下发
        int viewMinCX = (minX - CULL_MARGIN) >> 4;
        int viewMaxCX = (maxX + CULL_MARGIN) >> 4;
        int viewMinCZ = (minZ - CULL_MARGIN) >> 4;
        int viewMaxCZ = (maxZ + CULL_MARGIN) >> 4;

        List<MapOverlayDataPacket.TerritoryEntry> territories = new ArrayList<>();
        List<Territory> local = new ArrayList<>();
        for (Territory territory : TerritoryRegistry.INSTANCE.all()) {
            if (!dimension.equals(territory.dimension())) {
                continue;
            }
            Set<Long> keys = territory.chunks();
            long[] chunks = new long[keys.size()];
            int index = 0;
            int minCX = Integer.MAX_VALUE;
            int maxCX = Integer.MIN_VALUE;
            int minCZ = Integer.MAX_VALUE;
            int maxCZ = Integer.MIN_VALUE;
            for (long key : keys) {
                chunks[index++] = key;
                int chunkX = Territory.chunkKeyX(key);
                int chunkZ = Territory.chunkKeyZ(key);
                if (chunkX < minCX) {
                    minCX = chunkX;
                }
                if (chunkX > maxCX) {
                    maxCX = chunkX;
                }
                if (chunkZ < minCZ) {
                    minCZ = chunkZ;
                }
                if (chunkZ > maxCZ) {
                    maxCZ = chunkZ;
                }
            }
            if (!territoryScope
                    && (maxCX < viewMinCX || minCX > viewMaxCX
                    || maxCZ < viewMinCZ || minCZ > viewMaxCZ)) {
                continue;
            }
            local.add(territory);
            int population = TerritoryVitalsService.populationOf(territory);
            Nation nation = NationRegistry.INSTANCE.byId(territory.nationId());
            String nationLabel = nation != null ? nation.name() : territory.nationId();
            boolean self = own != null && own.id().equals(territory.id());
            territories.add(new MapOverlayDataPacket.TerritoryEntry(
                    territory.id(), territory.name(), ownerNameOf(player, territory.owner()),
                    nationLabel, TerritoryTier.labelOf(population), population,
                    territory.centerX(), territory.centerZ(),
                    chunks, self, false));
        }

        List<MapOverlayDataPacket.BuildingEntry> buildings = new ArrayList<>();
        for (BuildingRecord record : BuildingRegistry.INSTANCE.all()) {
            if (buildings.size() >= BUILDING_LIMIT) {
                break;
            }
            if (!dimension.equals(record.dimension())) {
                continue;
            }
            if (record.max().getX() < minX || record.min().getX() > maxX
                    || record.max().getZ() < minZ || record.min().getZ() > maxZ) {
                continue;
            }
            String territoryId = territoryIdOf(local, record);
            if (own == null || !own.id().equals(territoryId)) {
                continue;
            }
            com.xy2407.kingdom.common.building.BuildingMeta overlayMeta =
                    BuildingRegistry.INSTANCE.metaOf(record);
            String overlayKind = overlayMeta.work() != null
                    ? overlayMeta.work().name()
                    : (overlayMeta.type() == null ? "" : overlayMeta.type().name());
            buildings.add(new MapOverlayDataPacket.BuildingEntry(
                    record.id().toString(), record.name(),
                    overlayKind,
                    record.min().getX(), record.min().getZ(),
                    record.max().getX(), record.max().getZ(),
                    territoryId));
        }

        // W14+：世界道路仅下发给自己（地图私有），放大请求量过滤 owner 后按上限截断
        String viewerId = player.getUUID().toString();
        int scanMinX = minX >> 4;
        int scanMaxX = maxX >> 4;
        int scanMinZ = minZ >> 4;
        int scanMaxZ = maxZ >> 4;
        int roadLimit = ROAD_CHUNK_LIMIT;
        int searchCenterX = centerX >> 4;
        if (territoryScope && own != null) {
            int minCx = Integer.MAX_VALUE;
            int maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE;
            int maxCz = Integer.MIN_VALUE;
            for (long key : own.chunks()) {
                minCx = Math.min(minCx, Territory.chunkKeyX(key));
                maxCx = Math.max(maxCx, Territory.chunkKeyX(key));
                minCz = Math.min(minCz, Territory.chunkKeyZ(key));
                maxCz = Math.max(maxCz, Territory.chunkKeyZ(key));
            }
            scanMinX = minCx;
            scanMaxX = maxCx;
            scanMinZ = minCz;
            scanMaxZ = maxCz;
            roadLimit = Integer.MAX_VALUE;
        }
        List<MapOverlayDataPacket.RoadChunkEntry> roads = new ArrayList<>();
        for (MapOverlayDataPacket.RoadChunkEntry entry : RoadStore.INSTANCE.roadChunksIn(
                dimension, scanMinX, scanMaxX, scanMinZ, scanMaxZ,
                searchCenterX, centerZ >> 4, territoryScope ? Integer.MAX_VALUE : ROAD_CHUNK_LIMIT * 2)) {
            if (viewerId.equals(RoadStore.INSTANCE.roadOwner(dimension, entry.chunkX(), entry.chunkZ()))) {
                roads.add(entry);
                if (roads.size() >= roadLimit) {
                    break;
                }
            }
        }

        return new MapOverlayDataPacket(++revision, territories, buildings, roads);
    }

    private static String territoryIdOf(List<Territory> territories, BuildingRecord record) {
        int blockX = (record.min().getX() + record.max().getX()) / 2;
        int blockZ = (record.min().getZ() + record.max().getZ()) / 2;
        for (Territory territory : territories) {
            if (territory.containsBlock(blockX, blockZ)) {
                return territory.id();
            }
        }
        return "";
    }

    private static String ownerNameOf(ServerPlayer viewer, UUID owner) {
        if (owner == null) {
            return "";
        }
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(owner);
        if (profile != null && !profile.playerName().isBlank()) {
            return profile.playerName();
        }
        ServerPlayer online = viewer.getServer() == null ? null
                : viewer.getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return "";
    }
}
