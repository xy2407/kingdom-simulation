package com.xy2407.kingdom.server.area;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.PlotAreaSchema;

import net.minecraft.server.MinecraftServer;

/**
 * 作业范围管理器：伐木/养殖范围共用，懒加载 + upsert。
 */
public final class PlotAreaManager {

    public static final PlotAreaManager INSTANCE = new PlotAreaManager();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<UUID, PlotArea> areas = new HashMap<>();

    private boolean loaded;

    private int loadRetryCooldown;

    private PlotAreaManager() {
    }

    public void reset() {
        areas.clear();
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
            List<PlotArea> rows = KingdomPersistence.querySync(connection -> {
                List<PlotArea> result = new ArrayList<>();
                try (var st = connection.createStatement();
                     var rs = st.executeQuery("SELECT " + PlotAreaSchema.columns()
                             + " FROM " + PlotAreaSchema.TABLE_PLOT_AREA)) {
                    while (rs.next()) {
                        PlotArea area = readRow(rs);
                        if (area != null) {
                            result.add(area);
                        }
                    }
                }
                return result;
            });
            for (PlotArea area : rows) {
                areas.put(area.id(), area);
            }
            loaded = true;
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
        }
    }

    public List<PlotArea> all() {
        return List.copyOf(areas.values());
    }

    public List<PlotArea> all(PlotAreaKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<PlotArea> result = new ArrayList<>();
        for (PlotArea area : areas.values()) {
            if (area.kind() == kind) {
                result.add(area);
            }
        }
        return result;
    }

    public List<PlotArea> all(String dimension, PlotAreaKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<PlotArea> result = new ArrayList<>();
        for (PlotArea area : areas.values()) {
            if (area.kind() == kind && dimension != null && dimension.equals(area.dimension())) {
                result.add(area);
            }
        }
        return result;
    }

    public PlotArea byBuilding(UUID buildingId, PlotAreaKind kind) {
        if (buildingId == null || kind == null) {
            return null;
        }
        for (PlotArea area : areas.values()) {
            if (area.kind() == kind && buildingId.equals(area.buildingId())) {
                return area;
            }
        }
        return null;
    }

    public PlotArea upsert(UUID buildingId, PlotAreaKind kind, String dimension,
                           List<Long> cells, long createdDay) {
        if (buildingId == null || kind == null) {
            return null;
        }
        PlotArea existing = byBuilding(buildingId, kind);
        PlotArea area = existing == null
                ? new PlotArea(UUID.randomUUID(), buildingId, kind, dimension, cells, createdDay)
                : existing.withCells(cells);
        areas.put(area.id(), area);
        persist(area);
        return area;
    }

    public boolean remove(UUID id) {
        if (id == null || areas.remove(id) == null) {
            return false;
        }
        KingdomPersistence.delete(PlotAreaSchema.TABLE_PLOT_AREA,
                PlotAreaSchema.COL_UUID, id.toString());
        return true;
    }

    private void persist(PlotArea area) {
        KingdomPersistence.submit(PlotAreaSchema.TABLE_PLOT_AREA, PlotAreaSchema.COL_UUID,
                area.id().toString(), toValues(area));
    }

    private static Map<String, Object> toValues(PlotArea area) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PlotAreaSchema.COL_UUID, area.id().toString());
        values.put(PlotAreaSchema.COL_BUILDING_ID, area.buildingId().toString());
        values.put(PlotAreaSchema.COL_KIND, area.kind().id());
        values.put(PlotAreaSchema.COL_DIMENSION, area.dimension());
        values.put(PlotAreaSchema.COL_CELLS, joinCells(area.cells()));
        values.put(PlotAreaSchema.COL_CREATED_DAY, area.createdDay());
        values.put(PlotAreaSchema.COL_UPDATED_AT, System.currentTimeMillis());
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

    private static PlotArea readRow(ResultSet rs) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(rs.getString(PlotAreaSchema.COL_UUID));
        } catch (RuntimeException exception) {
            return null;
        }
        UUID buildingId;
        try {
            buildingId = UUID.fromString(rs.getString(PlotAreaSchema.COL_BUILDING_ID));
        } catch (RuntimeException exception) {
            return null;
        }
        PlotAreaKind kind = PlotAreaKind.parse(rs.getString(PlotAreaSchema.COL_KIND));
        if (kind == null) {
            return null;
        }
        return new PlotArea(id, buildingId, kind, rs.getString(PlotAreaSchema.COL_DIMENSION),
                parseCells(rs.getString(PlotAreaSchema.COL_CELLS)),
                rs.getLong(PlotAreaSchema.COL_CREATED_DAY));
    }
}
