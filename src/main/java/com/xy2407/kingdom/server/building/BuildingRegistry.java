package com.xy2407.kingdom.server.building;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingTask;
import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.building.BuildingWorkCatalog;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.server.mapoverlay.MapOverlayCollector;
import com.xy2407.kingdom.server.path.BuildingRoadAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 建筑登记表，负责完工落库、按包围盒定位建筑并提示玩家当前状态
 */
public final class BuildingRegistry {
    public static final BuildingRegistry INSTANCE = new BuildingRegistry();

    private static final int PROMPT_INTERVAL_TICKS = 20;
    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final String COMPLETED = "COMPLETED";

    private final List<BuildingRecord> records = new ArrayList<>();
    private final Map<UUID, BuildingRecord> index = new HashMap<>();
    private final Map<String, BuildingMeta> metaCache = new ConcurrentHashMap<>();
    private boolean loaded;
    private int tickCounter;
    private int loadRetryCooldown;

    private BuildingRegistry() {
    }

    public void reset() {
        records.clear();
        index.clear();
        loaded = false;
        metaCache.clear();
        BuildingMetaLoader.clearIndex();
        tickCounter = 0;
        loadRetryCooldown = 0;
    }

    public void register(BuildingTask task) {
        Vec3i size = task.plan().size();
        BlockPos min = task.origin();
        BlockPos max = min.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        BuildingMeta blueprint = BuildingMetaLoader.byBuildName(task.blueprintName());
        BuildingType type = blueprint != null && blueprint.type() != null
                ? blueprint.type()
                : BuildingMetaLoader.typeOfFolder(task.blueprintName());
        BuildingWork work = blueprint == null ? null : blueprint.work();
        if (work == null) {
            work = BuildingWorkCatalog.soleWork(type);
        }
        int farmSlots = BuildingLevelRules.farmSlotsOf(work, task.level());
        BuildingRecord building = new BuildingRecord(task.id(), task.blueprintName(),
                task.dimension().location().toString(), min, max, task.level(), task.rotation(),
                farmSlots, task.createdDay(), type, work);
        records.add(building);
        index.put(building.id(), building);
        KingdomPersistence.submit(BuildingSchema.TABLE_BUILDING, BuildingSchema.COL_UUID,
                building.id().toString(), toValues(building));
        MapOverlayCollector.pushAll();
        BuildingRoadAccess.markBuildingDirty(building.id());
    }

    public BuildingMeta metaOf(BuildingRecord building) {
        if (building == null || building.name() == null || building.name().isBlank()) {
            return BuildingMeta.fallback("", null);
        }
        BuildingWork work = building.work();
        if (work != null) {
            return BuildingWorkCatalog.withLevel(BuildingMetaLoader.templateForWork(work),
                    work, building.level());
        }
        BuildingMeta cached = metaCache.get(building.name());
        if (cached != null) {
            return cached;
        }
        BuildingMeta loadedMeta = BuildingMetaLoader.byBuildName(building.name());
        if (loadedMeta == null) {
            loadedMeta = BuildingMetaLoader.load(BuildingBlueprints.fileOf(building.name()),
                    building.name());
        }
        metaCache.put(building.name(), loadedMeta);
        return loadedMeta;
    }

    public int storageSlotsOf(BuildingRecord building) {
        return Math.max(0, metaOf(building).storageSlots());
    }

    public List<BuildingRecord> all() {
        return List.copyOf(records);
    }

    public BuildingRecord byId(UUID buildingId) {
        if (buildingId == null) {
            return null;
        }
        return index.get(buildingId);
    }

    public void update(BuildingRecord building) {
        if (building == null) {
            return;
        }
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(building.id())) {
                records.set(i, building);
                index.put(building.id(), building);
                break;
            }
        }
        KingdomPersistence.submit(BuildingSchema.TABLE_BUILDING, BuildingSchema.COL_UUID,
                building.id().toString(), toValues(building));
        MapOverlayCollector.pushAll();
        BuildingRoadAccess.markBuildingDirty(building.id());
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
        if (records.isEmpty() || ++tickCounter % PROMPT_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BuildingRecord hit = locate(player);
            if (hit != null) {
                player.displayClientMessage(prompt(hit), true);
            }
        }
    }

    private void load() {
        try {
            List<BuildingRecord> rows = KingdomPersistence.querySync(connection -> {
                List<BuildingRecord> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + BuildingSchema.columns()
                             + " FROM " + BuildingSchema.TABLE_BUILDING)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            records.clear();
            records.addAll(rows);
            index.clear();
            for (BuildingRecord row : rows) {
                index.put(row.id(), row);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 座建筑", records.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载建筑数据失败", exception);
        }
    }

    public BuildingRecord locate(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        for (BuildingRecord building : records) {
            if (building.contains(dimension, player.getX(), player.getY(), player.getZ())) {
                return building;
            }
        }
        return null;
    }

    public BuildingRecord at(String dimension, double x, double y, double z) {
        if (dimension == null || !loaded) {
            return null;
        }
        for (BuildingRecord building : records) {
            if (building.contains(dimension, x, y, z)) {
                return building;
            }
        }
        return null;
    }

    public BlockPos findDining(String dimensionId, double x, double y, double z, double radius) {
        if (!loaded || records.isEmpty() || dimensionId == null) {
            return null;
        }
        BlockPos best = null;
        double bestSqr = radius * radius;
        for (BuildingRecord record : records) {
            if (!dimensionId.equals(record.dimension()) || record.work() != BuildingWork.RESTAURANT) {
                continue;
            }
            double spotX = clamp(x, record.min().getX() - 1.0D, record.max().getX() + 2.0D);
            double spotZ = clamp(z, record.min().getZ() - 1.0D, record.max().getZ() + 2.0D);
            double dx = x - spotX;
            double dz = z - spotZ;
            double distSqr = dx * dx + dz * dz;
            if (distSqr <= bestSqr) {
                bestSqr = distSqr;
                best = BlockPos.containing(spotX, record.min().getY(), spotZ);
            }
        }
        return best;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static Component prompt(BuildingRecord building) {
        if (!building.typed() && BuildingWorkCatalog.needsChoice(building.type())) {
            return Component.literal("这座" + building.type().label() + "建筑还没定用途 · 按 Tab 选择");
        }
        return Component.literal(String.format("建筑 %s  包围盒 (%d,%d,%d)-(%d,%d,%d)  等级 %d",
                building.name(),
                building.min().getX(), building.min().getY(), building.min().getZ(),
                building.max().getX(), building.max().getY(), building.max().getZ(),
                building.level()));
    }

    private static Map<String, Object> toValues(BuildingRecord building) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(BuildingSchema.COL_UUID, building.id().toString());
        values.put(BuildingSchema.COL_NAME, building.name());
        values.put(BuildingSchema.COL_CODE, building.name());
        values.put(BuildingSchema.COL_STRUCTURE_NAME, building.name());
        values.put(BuildingSchema.COL_TYPE, building.type() == null ? "" : building.type().name());
        values.put(BuildingSchema.COL_CATEGORY, building.type() == null ? "" : building.type().label());
        values.put(BuildingSchema.COL_WORK, building.work() == null ? "" : building.work().name());
        values.put(BuildingSchema.COL_DIMENSION, building.dimension());
        values.put(BuildingSchema.COL_MIN_X, building.min().getX());
        values.put(BuildingSchema.COL_MIN_Y, building.min().getY());
        values.put(BuildingSchema.COL_MIN_Z, building.min().getZ());
        values.put(BuildingSchema.COL_MAX_X, building.max().getX());
        values.put(BuildingSchema.COL_MAX_Y, building.max().getY());
        values.put(BuildingSchema.COL_MAX_Z, building.max().getZ());
        values.put(BuildingSchema.COL_LEVEL, building.level());
        values.put(BuildingSchema.COL_ROTATION, building.rotation());
        values.put(BuildingSchema.COL_FARM_SLOTS, building.farmSlots());
        values.put(BuildingSchema.COL_BUILD_STATE, COMPLETED);
        values.put(BuildingSchema.COL_CREATED_DAY, building.createdDay());
        values.put(BuildingSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static BuildingRecord readRow(ResultSet rs) throws SQLException {
        String name = rs.getString(BuildingSchema.COL_NAME);
        BuildingMeta blueprint = BuildingMetaLoader.byBuildName(name);
        BuildingType type = blueprint != null && blueprint.type() != null
                ? blueprint.type()
                : BuildingType.parse(rs.getString(BuildingSchema.COL_TYPE));
        if (type == null) {
            type = BuildingMetaLoader.typeOfFolder(name);
        }
        BuildingWork work = BuildingWork.parse(rs.getString(BuildingSchema.COL_WORK));
        if (work == null && blueprint != null) {
            work = blueprint.work();
        }
        if (work == null) {
            work = BuildingWorkCatalog.soleWork(type);
        }
        return new BuildingRecord(
                UUID.fromString(rs.getString(BuildingSchema.COL_UUID)),
                name,
                rs.getString(BuildingSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(BuildingSchema.COL_MIN_X), rs.getInt(BuildingSchema.COL_MIN_Y),
                        rs.getInt(BuildingSchema.COL_MIN_Z)),
                new BlockPos(rs.getInt(BuildingSchema.COL_MAX_X), rs.getInt(BuildingSchema.COL_MAX_Y),
                        rs.getInt(BuildingSchema.COL_MAX_Z)),
                rs.getInt(BuildingSchema.COL_LEVEL),
                rs.getInt(BuildingSchema.COL_ROTATION),
                BuildingLevelRules.farmSlotsOf(work, rs.getInt(BuildingSchema.COL_LEVEL)),
                rs.getInt(BuildingSchema.COL_CREATED_DAY),
                type,
                work);
    }
}
