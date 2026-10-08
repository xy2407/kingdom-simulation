package com.xy2407.kingdom.server.farm;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.farm.FarmPlot;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.npc.NpcFarmService;
import com.xy2407.kingdom.common.persistence.FarmPlotSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 农田登记表：加载、落库，并唯一负责"格子额度不得超过建筑上限"的校验。
 */
public final class FarmPlotManager {
    public static final FarmPlotManager INSTANCE = new FarmPlotManager();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final int ASSIGN_INTERVAL_TICKS = 100;
    private static final int SURVEY_INTERVAL_TICKS = 1000;

    private final Map<UUID, FarmPlot> plots = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;
    private int assignCounter;
    private int surveyCounter;

    private FarmPlotManager() {
    }

    public void reset() {
        plots.clear();
        loaded = false;
        loadRetryCooldown = 0;
        assignCounter = 0;
        surveyCounter = 0;
        FarmLandRegistry.reset();
        NpcFarmService.reset();
    }

    public void tick(MinecraftServer server) {
        FarmLandRegistry.tick(server);
        if (loaded) {
            if (++assignCounter % ASSIGN_INTERVAL_TICKS == 0) {
                NpcFarmService.assignWorkers();
            }
            if (++surveyCounter % SURVEY_INTERVAL_TICKS == 0) {
                NpcFarmService.survey(server);
            }
            return;
        }
        if (loadRetryCooldown > 0) {
            loadRetryCooldown--;
            return;
        }
        if (KingdomPersistence.isReady()) {
            load();
        }
    }

    private void load() {
        try {
            List<FarmPlot> rows = KingdomPersistence.querySync(connection -> {
                List<FarmPlot> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + FarmPlotSchema.columns()
                             + " FROM " + FarmPlotSchema.TABLE_FARM_PLOT)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            plots.clear();
            for (FarmPlot plot : rows) {
                plots.put(plot.id(), plot);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 块农田", plots.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载农田数据失败", exception);
        }
    }

    public Collection<FarmPlot> all() {
        return plots.values();
    }

    public FarmPlot byId(UUID plotId) {
        return plotId == null ? null : plots.get(plotId);
    }

    public List<FarmPlot> plotsOf(UUID buildingId) {
        List<FarmPlot> result = new ArrayList<>();
        if (buildingId == null) {
            return result;
        }
        for (FarmPlot plot : plots.values()) {
            if (plot.buildingId().equals(buildingId)) {
                result.add(plot);
            }
        }
        return result;
    }

    public int usedSlots(UUID buildingId) {
        int used = 0;
        for (FarmPlot plot : plots.values()) {
            if (plot.buildingId().equals(buildingId)) {
                used += plot.slots();
            }
        }
        return used;
    }

    public int remainingSlots(BuildingRecord building) {
        if (building == null) {
            return 0;
        }
        return Math.max(0, building.farmSlots() - usedSlots(building.id()));
    }

    public FarmPlot create(BuildingRecord building, String name, int slots, int day) {
        return create(building, name, slots, day, BlockPos.ZERO, BlockPos.ZERO, "", List.of(), List.of());
    }

    public FarmPlot create(BuildingRecord building, String name, int slots, int day,
                           BlockPos min, BlockPos max, String cropId, List<UUID> workerIds,
                           List<FarmRegion> extra) {
        if (building == null) {
            return null;
        }
        int wanted = Math.max(0, Math.min(slots, remainingSlots(building)));
        FarmPlot plot = new FarmPlot(UUID.randomUUID(), building.id(), building.name(),
                name == null || name.isBlank() ? defaultName(building.id()) : name.trim(),
                wanted, cropId == null ? "" : cropId,
                workerIds == null ? List.of() : workerIds, building.dimension(),
                min == null ? BlockPos.ZERO : min, max == null ? BlockPos.ZERO : max, day,
                extra == null ? List.of() : extra);
        plots.put(plot.id(), plot);
        persist(plot);
        return plot;
    }

    public boolean update(FarmPlot plot) {
        if (plot == null) {
            return false;
        }
        FarmPlot existing = plots.get(plot.id());
        if (existing == null) {
            return false;
        }
        int usedByOthers = usedSlots(plot.buildingId()) - existing.slots();
        int cap = capacityOf(plot.buildingId(), existing);
        if (usedByOthers + Math.max(0, plot.slots()) > cap) {
            return false;
        }
        plots.put(plot.id(), plot);
        persist(plot);
        return true;
    }

    public boolean remove(UUID plotId) {
        if (plotId == null || plots.remove(plotId) == null) {
            return false;
        }
        KingdomPersistence.delete(FarmPlotSchema.TABLE_FARM_PLOT, FarmPlotSchema.COL_UUID, plotId.toString());
        return true;
    }

    private static int capacityOf(UUID buildingId, FarmPlot fallback) {
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(buildingId);
        if (building != null) {
            return building.farmSlots();
        }
        return fallback == null ? 0 : fallback.slots();
    }

    private static String defaultName(UUID buildingId) {
        return "农田 " + Integer.toHexString(buildingId.hashCode() & 0xFF).toUpperCase();
    }

    private void persist(FarmPlot plot) {
        KingdomPersistence.submit(FarmPlotSchema.TABLE_FARM_PLOT, FarmPlotSchema.COL_UUID,
                plot.id().toString(), toValues(plot));
    }

    private static Map<String, Object> toValues(FarmPlot plot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(FarmPlotSchema.COL_UUID, plot.id().toString());
        values.put(FarmPlotSchema.COL_BUILDING_ID, plot.buildingId().toString());
        values.put(FarmPlotSchema.COL_BLUEPRINT_NAME, plot.blueprintName());
        values.put(FarmPlotSchema.COL_NAME, plot.name());
        values.put(FarmPlotSchema.COL_SLOTS, plot.slots());
        values.put(FarmPlotSchema.COL_CROP_ID, plot.cropId());
        values.put(FarmPlotSchema.COL_WORKER_IDS, joinWorkers(plot.workerIds()));
        values.put(FarmPlotSchema.COL_DIMENSION, plot.dimension());
        values.put(FarmPlotSchema.COL_MIN_X, plot.min().getX());
        values.put(FarmPlotSchema.COL_MIN_Y, plot.min().getY());
        values.put(FarmPlotSchema.COL_MIN_Z, plot.min().getZ());
        values.put(FarmPlotSchema.COL_MAX_X, plot.max().getX());
        values.put(FarmPlotSchema.COL_MAX_Y, plot.max().getY());
        values.put(FarmPlotSchema.COL_MAX_Z, plot.max().getZ());
        values.put(FarmPlotSchema.COL_CREATED_DAY, plot.createdDay());
        values.put(FarmPlotSchema.COL_UPDATED_AT, System.currentTimeMillis());
        values.put(FarmPlotSchema.COL_EXTRA_REGIONS, FarmRegion.encode(plot.extra()));
        return values;
    }

    private static FarmPlot readRow(ResultSet rs) throws SQLException {
        return new FarmPlot(
                UUID.fromString(rs.getString(FarmPlotSchema.COL_UUID)),
                parseUuid(rs.getString(FarmPlotSchema.COL_BUILDING_ID)),
                rs.getString(FarmPlotSchema.COL_BLUEPRINT_NAME),
                rs.getString(FarmPlotSchema.COL_NAME),
                rs.getInt(FarmPlotSchema.COL_SLOTS),
                rs.getString(FarmPlotSchema.COL_CROP_ID),
                parseWorkers(rs.getString(FarmPlotSchema.COL_WORKER_IDS)),
                rs.getString(FarmPlotSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(FarmPlotSchema.COL_MIN_X), rs.getInt(FarmPlotSchema.COL_MIN_Y),
                        rs.getInt(FarmPlotSchema.COL_MIN_Z)),
                new BlockPos(rs.getInt(FarmPlotSchema.COL_MAX_X), rs.getInt(FarmPlotSchema.COL_MAX_Y),
                        rs.getInt(FarmPlotSchema.COL_MAX_Z)),
                rs.getInt(FarmPlotSchema.COL_CREATED_DAY),
                FarmRegion.decode(rs.getString(FarmPlotSchema.COL_EXTRA_REGIONS)));
    }

    static String joinWorkers(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    static List<UUID> parseWorkers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            UUID id = parseUuid(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
