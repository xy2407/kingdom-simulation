package com.xy2407.kingdom.server.mapoverlay;

import java.util.ArrayList;
import java.util.List;
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
 */
public final class MapOverlayCollector {

    private static final int BUILDING_LIMIT = 512;

    private static final int ROAD_CHUNK_LIMIT = 128;

    private static final int PUSH_RADIUS = 512;

    private MapOverlayCollector() {
    }

    private static MinecraftServer server;

    private static long[] chunkArray(Set<Long> chunks) {
        long[] keys = new long[chunks.size()];
        int index = 0;
        for (long key : chunks) {
            keys[index++] = key;
        }
        return keys;
    }

    public static void install(MinecraftServer minecraftServer) {
        server = minecraftServer;
        MapOverlayHooks.requestHandler = MapOverlayCollector::onRequest;
    }

    public static void pushAll() {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, collect(player,
                    (int) Math.round(player.getX()), (int) Math.round(player.getZ()), PUSH_RADIUS));
        }
    }

    private static void onRequest(ServerPlayer player, MapOverlayRequestPacket packet) {
        PacketDistributor.sendToPlayer(player, collect(
                player, packet.centerX(), packet.centerZ(), packet.radius()));
    }

    public static MapOverlayDataPacket collect(ServerPlayer player, int centerX, int centerZ, int radius) {
        String dimension = player.level().dimension().location().toString();
        Territory own = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());

        List<MapOverlayDataPacket.TerritoryEntry> territories = new ArrayList<>();
        List<Territory> local = new ArrayList<>();
        for (Territory territory : TerritoryRegistry.INSTANCE.all()) {
            if (!dimension.equals(territory.dimension())) {
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
                    chunkArray(territory.chunks()), self, false));
        }

        int radiusValue = Math.max(16, radius);
        int minX = centerX - radiusValue;
        int maxX = centerX + radiusValue;
        int minZ = centerZ - radiusValue;
        int maxZ = centerZ + radiusValue;
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
            buildings.add(new MapOverlayDataPacket.BuildingEntry(
                    record.id().toString(), record.name(),
                    BuildingRegistry.INSTANCE.metaOf(record).type().name(),
                    record.min().getX(), record.min().getZ(),
                    record.max().getX(), record.max().getZ(),
                    territoryId));
        }

        // W14+：世界道路仅下发给自己（地图私有），放大请求量过滤 owner 后按上限截断
        String viewerId = player.getUUID().toString();
        List<MapOverlayDataPacket.RoadChunkEntry> roads = new ArrayList<>();
        for (MapOverlayDataPacket.RoadChunkEntry entry : RoadStore.INSTANCE.roadChunksIn(
                dimension, minX >> 4, maxX >> 4, minZ >> 4, maxZ >> 4,
                centerX >> 4, centerZ >> 4, ROAD_CHUNK_LIMIT * 2)) {
            if (viewerId.equals(RoadStore.INSTANCE.roadOwner(dimension, entry.chunkX(), entry.chunkZ()))) {
                roads.add(entry);
                if (roads.size() >= ROAD_CHUNK_LIMIT) {
                    break;
                }
            }
        }

        return new MapOverlayDataPacket(System.nanoTime(), territories, buildings, roads);
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
   