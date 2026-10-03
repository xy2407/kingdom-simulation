package com.xy2407.kingdom.server.building;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingTask;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 建筑登记表，完工落库并在玩家进入包围盒时提示
 */
public final class BuildingRegistry {
    public static final BuildingRegistry INSTANCE = new BuildingRegistry();

    private static final int PROMPT_INTERVAL_TICKS = 20;
    private static final int LOAD_RETRY_DELAY_TICKS = 200;
    private static final String COMPLETED = "COMPLETED";
    private static final String[] DINING_WORDS = {
            "restaurant", "dining", "canteen", "tavern", "cafe", "eatery",
            "餐厅", "饭馆", "餐馆", "饭店", "食堂", "酒馆", "小吃"};

    private final List<BuildingRecord> records = new ArrayList<>();
    private final Map<String, Boolean> diningCache = new ConcurrentHashMap<>();
    private boolean loaded;
    private int tickCounter;
    private int loadRetryCooldown;

    private BuildingRegistry() {
    }

    public void reset() {
        records.clear();
        loaded = false;
        tickCounter = 0;
        loadRetryCooldown = 0;
    }

    public void register(BuildingTask task) {
        Vec3i size = task.plan().size();
        BlockPos min = task.origin();
        BlockPos max = min.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        BuildingRecord building = new BuildingRecord(task.id(), task.blueprintName(),
                task.dimension().location().toString(), min, max, task.level(), task.rotation(),
                farmSlotsOf(task.blueprintName()), task.createdDay());
        records.add(building);
        KingdomPersistence.submit(BuildingSchema.TABLE_BUILDING, BuildingSchema.COL_UUID,
                building.id().toString(), toValues(building));
    }

    private static int farmSlotsOf(String blueprintName) {
        BuildingMeta meta = BuildingMetaLoader.load(BuildingBlueprints.fileOf(blueprintName), blueprintName);
        return meta.farmSlots();
    }

    public BuildingRecord byId(UUID buildingId) {
        if (buildingId == null) {
            return null;
        }
        for (BuildingRecord record : records) {
            if (record.id().equals(buildingId)) {
                return record;
            }
        }
        return null;
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
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 座建筑", records.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载建筑数据失败", exception);
        }
    }

    /**
     * 找出玩家当前所处的建筑；不在任何建筑内时返回 {@code null}。
     *
     * <p>公开给建筑操作面板用：按 Tab 时由服务端按玩家实际位置判定，不采信客户端坐标。</p>
     */
    public BuildingRecord locate(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        for (BuildingRecord building : records) {
            if (building.contains(dimension, player.getX(), player.getY(), player.getZ())) {
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
            if (!dimensionId.equals(record.dimension()) || !isDining(record.name())) {
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

    /** 某蓝图是不是餐厅（商业类 + 二级分类命中餐厅词），结果按蓝图名缓存。 */
    private boolean isDining(String blueprintName) {
        if (blueprintName == null || blueprintName.isBlank()) {
            return false;
        }
        return diningCache.computeIfAbsent(blueprintName, BuildingRegistry::resolveDining);
    }

    private static boolean resolveDining(String blueprintName) {
        BuildingMeta meta = BuildingMetaLoader.load(BuildingBlueprints.fileOf(blueprintName), blueprintName);
        if (meta.type() != BuildingType.COMMERCIAL) {
            return false;
        }
        for (String tag : meta.tags()) {
            if (isDiningWord(tag)) {
                return true;
            }
        }
        return isDiningWord(BuildingMeta.shortName(blueprintName));
    }

    private static boolean isDiningWord(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (String word : DINING_WORDS) {
            if (key.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Component prompt(BuildingRecord building) {
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
        values.put(BuildingSchema.COL_STRUCTURE_NAME, building.name());
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
        return new BuildingRecord(
                UUID.fromString(rs.getString(BuildingSchema.COL_UUID)),
                rs.getString(BuildingSchema.COL_NAME),
                rs.getString(BuildingSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(BuildingSchema.COL_MIN_X), rs.getInt(BuildingSchema.COL_MIN_Y),
                        rs.getInt(BuildingSchema.COL_MIN_Z)),
                new BlockPos(rs.getInt(BuildingSchema.COL_MAX_X), rs.getInt(BuildingSchema.COL_MAX_Y),
                        rs.getInt(BuildingSchema.COL_MAX_Z)),
                rs.getInt(BuildingSchema.COL_LEVEL),
                rs.getInt(BuildingSchema.COL_ROTATION),
                rs.getInt(BuildingSchema.COL_FARM_SLOTS),
                rs.getInt(BuildingSchema.COL_CREATED_DAY));
    }
}
