package com.xy2407.kingdom.client.map;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayDataPacket;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayRequestPacket;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端地图叠层缓存：保存服务端下发的领地与建筑列表，并持有小地图与全屏地图共享的选中项。
 */
@OnlyIn(Dist.CLIENT)
public final class MapOverlayStore {

    private static final long REQUEST_GAP_NANOS = 1_000_000_000L;
    private static final long REFRESH_NANOS = 30_000_000_000L;
    private static final int MOVE_THRESHOLD_BLOCKS = 64;
    private static final int REQUEST_RADIUS_MIN = 64;

    public record TerritoryView(String id, String name, String ownerName, String nationLabel,
                                String tier, int population, int centerX, int centerZ,
                                Set<Long> chunks, boolean self, boolean ally) {

        public boolean containsChunk(int chunkX, int chunkZ) {
            return chunks.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }

        public int chunkCount() {
            return chunks.size();
        }

        public int centerBlockX() {
            return centerX;
        }

        public int centerBlockZ() {
            return centerZ;
        }
    }

    public record BuildingView(String id, String name, String type,
                               int minX, int minZ, int maxX, int maxZ, String territoryId) {

        public int centerBlockX() {
            return (minX + maxX) / 2;
        }

        public int centerBlockZ() {
            return (minZ + maxZ) / 2;
        }
    }

    public record RoadView(int chunkX, int chunkZ, long[] cells) {

        public boolean containsBlock(int worldX, int worldZ) {
            for (long cell : cells) {
                if (BlockPos.getX(cell) == worldX && BlockPos.getZ(cell) == worldZ) {
                    return true;
                }
            }
            return false;
        }

        public boolean containsChunk(int x, int z) {
            return chunkX == x && chunkZ == z;
        }
    }

    private static final List<TerritoryView> TERRITORIES = new ArrayList<>();
    private static final List<BuildingView> BUILDINGS = new ArrayList<>();
    private static final List<RoadView> ROADS = new ArrayList<>();

    private static Runnable changeListener = () -> {
    };

    private static long revision;
    private static String selectedId = "";
    private static boolean selectedIsTerritory;
    private static TerritoryView selectedTerritory;
    private static BuildingView selectedBuilding;
    private static long lastRequestNanos;
    private static int lastCenterX = Integer.MIN_VALUE;
    private static int lastCenterZ = Integer.MIN_VALUE;
    private static int lastRadius = -1;

    private MapOverlayStore() {
    }

    public static void accept(MapOverlayDataPacket packet) {
        TERRITORIES.clear();
        BUILDINGS.clear();
        ROADS.clear();
        List<MapOverlayDataPacket.TerritoryEntry> territories = packet.territories();
        for (int index = 0; index < territories.size(); index++) {
            MapOverlayDataPacket.TerritoryEntry entry = territories.get(index);
            Set<Long> chunkSet = new HashSet<>();
            for (long key : entry.chunks()) {
                chunkSet.add(key);
            }
            TERRITORIES.add(new TerritoryView(entry.id(), entry.name(), entry.ownerName(),
                    entry.nationLabel(), entry.tier(), entry.population(),
                    entry.centerX(), entry.centerZ(),
                    chunkSet, entry.self(), entry.ally()));
        }
        List<MapOverlayDataPacket.BuildingEntry> buildings = packet.buildings();
        for (int index = 0; index < buildings.size(); index++) {
            MapOverlayDataPacket.BuildingEntry entry = buildings.get(index);
            BUILDINGS.add(new BuildingView(entry.id(), entry.name(), entry.type(),
                    entry.minX(), entry.minZ(), entry.maxX(), entry.maxZ(), entry.territoryId()));
        }
        List<MapOverlayDataPacket.RoadChunkEntry> roads = packet.roads();
        for (int index = 0; index < roads.size(); index++) {
            MapOverlayDataPacket.RoadChunkEntry entry = roads.get(index);
            ROADS.add(new RoadView(entry.chunkX(), entry.chunkZ(), entry.cells()));
        }
        revision++;
        if (!selectedId.isEmpty() && !contains(selectedId)) {
            clearSelection();
        }
        changeListener.run();
    }

    public static void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> {
        } : listener;
    }

    public static void clear() {
        TERRITORIES.clear();
        BUILDINGS.clear();
        ROADS.clear();
        revision++;
        clearSelection();
        lastCenterX = Integer.MIN_VALUE;
        lastCenterZ = Integer.MIN_VALUE;
        lastRadius = -1;
    }

    public static void requestUpdate(int centerX, int centerZ, int radius) {
        int wantedRadius = Math.max(REQUEST_RADIUS_MIN, radius);
        long now = System.nanoTime();
        if (now - lastRequestNanos < REQUEST_GAP_NANOS) {
            return;
        }
        boolean moved = Math.abs(centerX - lastCenterX) > MOVE_THRESHOLD_BLOCKS
                || Math.abs(centerZ - lastCenterZ) > MOVE_THRESHOLD_BLOCKS;
        boolean resized = wantedRadius != lastRadius;
        if (!moved && !resized && now - lastRequestNanos < REFRESH_NANOS) {
            return;
        }
        lastRequestNanos = now;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastRadius = wantedRadius;
        PacketDistributor.sendToServer(new MapOverlayRequestPacket(centerX, centerZ, wantedRadius));
    }

    public static List<TerritoryView> territories() {
        return TERRITORIES;
    }

    public static List<BuildingView> buildings() {
        return BUILDINGS;
    }

    public static List<RoadView> roads() {
        return ROADS;
    }

    public static long revision() {
        return revision;
    }

    public static String selectedId() {
        return selectedId;
    }

    public static boolean selectedIsTerritory() {
        return selectedIsTerritory;
    }

    public static TerritoryView selectedTerritory() {
        return selectedTerritory;
    }

    public static BuildingView selectedBuilding() {
        return selectedBuilding;
    }

    public static void selectTerritory(TerritoryView territory) {
        selectedId = territory == null ? "" : territory.id();
        selectedIsTerritory = territory != null;
        selectedTerritory = territory;
        selectedBuilding = null;
        revision++;
        changeListener.run();
    }

    public static void selectBuilding(BuildingView building) {
        selectedId = building == null ? "" : building.id();
        selectedIsTerritory = false;
        selectedBuilding = building;
        selectedTerritory = null;
        revision++;
        changeListener.run();
    }

    public static void clearSelection() {
        if (selectedId.isEmpty() && selectedTerritory == null && selectedBuilding == null) {
            return;
        }
        selectedId = "";
        selectedIsTerritory = false;
        selectedTerritory = null;
        selectedBuilding = null;
        revision++;
        changeListener.run();
    }

    private static boolean contains(String id) {
        for (TerritoryView territory : TERRITORIES) {
            if (territory.id().equals(id)) {
                return true;
            }
        }
        for (BuildingView building : BUILDINGS) {
            if (building.id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
