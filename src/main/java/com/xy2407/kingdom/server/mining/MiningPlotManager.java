package com.xy2407.kingdom.server.mining;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.MiningPlotSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 采矿场管理器：懒加载落库数据，创建更新删除统一走 KingdomPersistence。
 */
public final class MiningPlotManager {

    public static final MiningPlotManager INSTANCE = new MiningPlotManager();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<UUID, MiningPlot> plots = new HashMap<>();

    private boolean loaded;

    private int loadRetryCooldown;

    private MiningPlotManager() {
    }

    public void reset() {
        plots.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public void tick(MinecraftServer server) {
        if (loaded) {
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
            List<MiningPlot> rows = KingdomPersistence.querySync(connection -> {
                List<MiningPlot> result = new ArrayList<>();
                try (var st = connection.createStatement();
                     var rs = st.executeQuery("SELECT " + MiningPlotSchema.columns()
                             + " FROM " + MiningPlotSchema.TABLE_MINING_PLOT)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            for (MiningPlot plot : rows) {
                if (plot != null) {
                    plots.putIfAbsent(plot.id(), plot);
                }
            }
            loaded = true;
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
        }
    }

    public List<MiningPlot> all() {
        return List.copyOf(plots.values());
    }

    public MiningPlot byId(UUID id) {
        return id == null ? null : plots.get(id);
    }

    public MiningPlot byBuilding(UUID buildingId) {
        if (buildingId == null) {
            return null;
        }
        for (MiningPlot plot : plots.values()) {
            if (plot.buildingId().equals(buildingId)) {
                return plot;
            }
        }
        return null;
    }

    public List<MiningPlot> allOf(UUID buildingId) {
        List<MiningPlot> result = new ArrayList<>();
        if (buildingId == null) {
            return result;
        }
        for (MiningPlot plot : plots.values()) {
            if (plot.buildingId().equals(buildingId)) {
                result.add(plot);
            }
        }
        return result;
    }

    public MiningPlot create(BuildingRecord building, String name, BlockPos min, BlockPos max,
                             int surfaceY, List<UUID> workerIds, long day, List<Long> cells) {
        if (building == null || min == null || max == null) {
            return null;
        }
        if (allOf(building.id()).size() >= BuildingLevelRules.industryPlotLimit(building.level())) {
            return null;
        }
        MiningPlot plot = new MiningPlot(UUID.randomUUID(), building.id(),
                name == null || name.isBlank() ? "矿场" : name.trim(),
                building.dimension(), min, max, surfaceY, surfaceY - 1, 0,
                MiningPlot.STATUS_RUNNING, workerIds, day, cells);
        plots.put(plot.id(), plot);
        persist(plot);
        MiningScanService.register(plot.id());
        return plot;
    }

    public void update(MiningPlot plot) {
        if (plot == null || !plots.containsKey(plot.id())) {
            return;
        }
        plots.put(plot.id(), plot);
        persist(plot);
        if (MiningPlot.STATUS_RUNNING.equals(plot.status()) && plot.hasArea()) {
            MiningScanService.register(plot.id());
        }
    }

    public boolean remove(UUID id) {
        if (id == null || plots.remove(id) == null) {
            return false;
        }
        MiningScanService.unregister(id);
        KingdomPersistence.delete(MiningPlotSchema.TABLE_MINING_PLOT,
                MiningPlotSchema.COL_UUID, id.toString());
        return true;
    }

    private void persist(MiningPlot plot) {
        KingdomPersistence.submit(MiningPlotSchema.TABLE_MINING_PLOT, MiningPlotSchema.COL_UUID,
                plot.id().toString(), toValues(plot));
    }

    private static Map<String, Object> toValues(MiningPlot plot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(MiningPlotSchema.COL_UUID, plot.id().toString());
        values.put(MiningPlotSchema.COL_BUILDING_ID, plot.buildingId().toString());
        values.put(MiningPlotSchema.COL_NAME, plot.name());
        values.put(MiningPlotSchema.COL_DIMENSION, plot.dimension());
        values.put(MiningPlotSchema.COL_MIN_X, plot.min().getX());
        values.put(MiningPlotSchema.COL_MIN_Y, plot.min().getY());
        values.put(MiningPlotSchema.COL_MIN_Z, plot.min().getZ());
        values.put(MiningPlotSchema.COL_MAX_X, plot.max().getX());
        values.put(MiningPlotSchema.COL_MAX_Y, plot.max().getY());
        values.put(MiningPlotSchema.COL_MAX_Z, plot.max().getZ());
        values.put(MiningPlotSchema.COL_SURFACE_Y, plot.surfaceY());
        values.put(MiningPlotSchema.COL_CURRENT_Y, plot.currentY());
        values.put(MiningPlotSchema.COL_CURSOR, plot.cursor());
        values.put(MiningPlotSchema.COL_STATUS, plot.status());
        values.put(MiningPlotSchema.COL_WORKER_IDS, joinWorkers(plot.workerIds()));
        values.put(MiningPlotSchema.COL_CREATED_DAY, plot.createdDay());
        values.put(MiningPlotSchema.COL_UPDATED_AT, System.currentTimeMillis());
        values.put(MiningPlotSchema.COL_CELLS, joinCells(plot.cells()));
        return values;
    }

    private static String joinCells(List<Long> cells) {
        if (cells == null || cells.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder(cells.size() * 20);
        for (long cell : cells) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(cell);
        }
        return joined.toString();
    }

    private static List<Long> parseCells(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> cells = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                cells.add(Long.parseLong(part.trim()));
            } catch (RuntimeException ignored) {
            }
        }
        return cells;
    }

    private static MiningPlot readRow(ResultSet rs) throws SQLException {
        return new MiningPlot(
                UUID.fromString(rs.getString(MiningPlotSchema.COL_UUID)),
                parseUuid(rs.getString(MiningPlotSchema.COL_BUILDING_ID)),
                rs.getString(MiningPlotSchema.COL_NAME),
                rs.getString(MiningPlotSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(MiningPlotSchema.COL_MIN_X), rs.getInt(MiningPlotSchema.COL_MIN_Y),
                        rs.getInt(MiningPlotSchema.COL_MIN_Z)),
                new BlockPos(rs.getInt(MiningPlotSchema.COL_MAX_X), rs.getInt(MiningPlotSchema.COL_MAX_Y),
                        rs.getInt(MiningPlotSchema.COL_MAX_Z)),
                rs.getInt(MiningPlotSchema.COL_SURFACE_Y),
                rs.getInt(MiningPlotSchema.COL_CURRENT_Y),
                rs.getInt(MiningPlotSchema.COL_CURSOR),
                rs.getString(MiningPlotSchema.COL_STATUS),
                parseWorkers(rs.getString(MiningPlotSchema.COL_WORKER_IDS)),
                rs.getLong(MiningPlotSchema.COL_CREATED_DAY),
                parseCells(rs.getString(MiningPlotSchema.COL_CELLS)));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static List<UUID> parseWorkers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (RuntimeException ignored) {
            }
        }
        return ids;
    }

    private static String joinWorkers(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (UUID id : ids) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        return joined.toString();
    }
}
