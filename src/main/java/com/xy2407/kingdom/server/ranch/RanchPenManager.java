package com.xy2407.kingdom.server.ranch;

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
import com.xy2407.kingdom.common.building.AnimalSpec;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RanchSchema;
import com.xy2407.kingdom.common.ranch.RanchBounds;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 养殖栏登记表：加载落库，并按已完工的养殖建筑自动补栏。
 */
public final class RanchPenManager {

    public static final RanchPenManager INSTANCE = new RanchPenManager();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final int SCAN_INTERVAL_TICKS = 200;

    private final Map<UUID, RanchPen> pens = new LinkedHashMap<>();
    private boolean loaded;
    private int loadRetryCooldown;
    private int scanCounter;

    private RanchPenManager() {
    }

    public void reset() {
        pens.clear();
        loaded = false;
        loadRetryCooldown = 0;
        scanCounter = 0;
        RanchBounds.reset();
    }

    public void tick(MinecraftServer server) {
        if (!loaded) {
            if (loadRetryCooldown > 0) {
                loadRetryCooldown--;
                return;
            }
            if (KingdomPersistence.isReady()) {
                load();
            }
            return;
        }
        if (++scanCounter % SCAN_INTERVAL_TICKS == 0) {
            ensurePens();
        }
    }

    public Collection<RanchPen> all() {
        List<RanchPen> enabled = new ArrayList<>();
        for (RanchPen pen : pens.values()) {
            if (isEnabled(pen)) {
                enabled.add(pen);
            }
        }
        return enabled;
    }

    public Collection<RanchPen> allRaw() {
        return pens.values();
    }

    public RanchPen byId(UUID penId) {
        return penId == null ? null : pens.get(penId);
    }

    public List<RanchPen> pensOf(UUID buildingId) {
        List<RanchPen> result = new ArrayList<>();
        if (buildingId == null) {
            return result;
        }
        for (RanchPen pen : pens.values()) {
            if (pen.buildingId().equals(buildingId)) {
                result.add(pen);
            }
        }
        return result;
    }

    public RanchPen byKey(UUID buildingId, String animalId) {
        for (RanchPen pen : pens.values()) {
            if (pen.buildingId().equals(buildingId) && pen.animalId().equals(animalId)) {
                return pen;
            }
        }
        return null;
    }

    public AnimalSpec animalOf(RanchPen pen) {
        if (pen == null) {
            return null;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(pen.buildingId());
        for (AnimalSpec spec : animalsOf(building)) {
            if (spec.id().equals(pen.animalId())) {
                return spec;
            }
        }
        return null;
    }

    public List<AnimalSpec> animalsOf(BuildingRecord building) {
        if (building == null) {
            return List.of();
        }
        return BuildingRegistry.INSTANCE.metaOf(building).animals();
    }

    public List<String> enabledAnimalIds(BuildingRecord building) {
        if (building == null) {
            return List.of();
        }
        List<AnimalSpec> specs = animalsOf(building);
        int limit = BuildingLevelRules.ranchPenLimit(building.level());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < specs.size() && i < limit; i++) {
            ids.add(specs.get(i).id());
        }
        return ids;
    }

    public boolean isEnabled(RanchPen pen) {
        if (pen == null) {
            return false;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.byId(pen.buildingId());
        return building != null && enabledAnimalIds(building).contains(pen.animalId());
    }

    public RanchPen update(RanchPen pen) {
        if (pen == null) {
            return null;
        }
        pens.put(pen.id(), pen);
        persist(pen);
        return pen;
    }

    public boolean remove(UUID penId) {
        if (penId == null || pens.remove(penId) == null) {
            return false;
        }
        KingdomPersistence.delete(RanchSchema.TABLE_RANCH_PEN, RanchSchema.COL_UUID, penId.toString());
        return true;
    }

    private void ensurePens() {
        for (RanchPen pen : new ArrayList<>(pens.values())) {
            if (BuildingRegistry.INSTANCE.byId(pen.buildingId()) == null) {
                remove(pen.id());
            }
        }
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            for (AnimalSpec spec : animalsOf(building)) {
                if (spec.id().isBlank()) {
                    continue;
                }
                RanchPen existing = byKey(building.id(), spec.id());
                if (existing != null) {
                    continue;
                }
                RanchPen pen = new RanchPen(UUID.randomUUID(), building.id(), building.name(), spec.id(),
                        building.dimension(), building.min(), building.max(), List.of(), false, "", 0L);
                pens.put(pen.id(), pen);
                persist(pen);
                ModMain.LOGGER.info("已为 {} 补出一栏养殖：{}", building.name(), spec.name());
            }
        }
    }

    private void load() {
        try {
            List<RanchPen> rows = KingdomPersistence.querySync(connection -> {
                List<RanchPen> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + RanchSchema.COLUMNS
                             + " FROM " + RanchSchema.TABLE_RANCH_PEN)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            pens.clear();
            for (RanchPen pen : rows) {
                pens.put(pen.id(), pen);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 栏养殖", pens.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载养殖栏失败", exception);
        }
    }

    private void persist(RanchPen pen) {
        KingdomPersistence.submit(RanchSchema.TABLE_RANCH_PEN, RanchSchema.COL_UUID,
                pen.id().toString(), toValues(pen));
    }

    private static Map<String, Object> toValues(RanchPen pen) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RanchSchema.COL_UUID, pen.id().toString());
        values.put(RanchSchema.COL_BUILDING_ID, pen.buildingId().toString());
        values.put(RanchSchema.COL_BLUEPRINT_NAME, pen.blueprintName());
        values.put(RanchSchema.COL_ANIMAL_ID, pen.animalId());
        values.put(RanchSchema.COL_DIMENSION, pen.dimension());
        values.put(RanchSchema.COL_MIN_X, pen.min().getX());
        values.put(RanchSchema.COL_MIN_Y, pen.min().getY());
        values.put(RanchSchema.COL_MIN_Z, pen.min().getZ());
        values.put(RanchSchema.COL_MAX_X, pen.max().getX());
        values.put(RanchSchema.COL_MAX_Y, pen.max().getY());
        values.put(RanchSchema.COL_MAX_Z, pen.max().getZ());
        values.put(RanchSchema.COL_KEEPER_IDS, joinKeepers(pen.keeperIds()));
        values.put(RanchSchema.COL_RUNNING, pen.running() ? 1 : 0);
        values.put(RanchSchema.COL_STATUS_KEY, pen.statusKey());
        values.put(RanchSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static RanchPen readRow(ResultSet rs) throws SQLException {
        return new RanchPen(
                UUID.fromString(rs.getString(RanchSchema.COL_UUID)),
                UUID.fromString(rs.getString(RanchSchema.COL_BUILDING_ID)),
                rs.getString(RanchSchema.COL_BLUEPRINT_NAME),
                rs.getString(RanchSchema.COL_ANIMAL_ID),
                rs.getString(RanchSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(RanchSchema.COL_MIN_X), rs.getInt(RanchSchema.COL_MIN_Y),
                        rs.getInt(RanchSchema.COL_MIN_Z)),
                new BlockPos(rs.getInt(RanchSchema.COL_MAX_X), rs.getInt(RanchSchema.COL_MAX_Y),
                        rs.getInt(RanchSchema.COL_MAX_Z)),
                parseKeepers(rs.getString(RanchSchema.COL_KEEPER_IDS)),
                rs.getInt(RanchSchema.COL_RUNNING) != 0,
                rs.getString(RanchSchema.COL_STATUS_KEY),
                rs.getLong(RanchSchema.COL_UPDATED_AT));
    }

    static String joinKeepers(List<UUID> ids) {
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

    static List<UUID> parseKeepers(String raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ids;
    }
}
