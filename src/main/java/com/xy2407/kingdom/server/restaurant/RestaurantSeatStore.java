package com.xy2407.kingdom.server.restaurant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RestaurantSeatSchema;
import com.xy2407.kingdom.common.restaurant.RestaurantSeat;
import com.xy2407.kingdom.common.restaurant.RestaurantSeats;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 餐厅座位内存表：开业扫描结果落库后缓存，供点单与上菜按建筑直接查询
 */
public final class RestaurantSeatStore {

    public static final RestaurantSeatStore INSTANCE = new RestaurantSeatStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<UUID, List<RestaurantSeat>> byBuilding = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private RestaurantSeatStore() {
    }

    public void reset() {
        byBuilding.clear();
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

    public boolean isLoaded() {
        return loaded;
    }

    public boolean hasTableSeat(UUID buildingId) {
        for (RestaurantSeat seat : seatsOf(buildingId)) {
            if (seat.table() != null) {
                return true;
            }
        }
        return false;
    }

    public List<RestaurantSeat> seatsOf(UUID buildingId) {
        if (buildingId == null) {
            return List.of();
        }
        return byBuilding.getOrDefault(buildingId, List.of());
    }

    public BlockPos servePointOf(UUID buildingId, BlockPos seat) {
        if (seat == null) {
            return null;
        }
        for (RestaurantSeat candidate : seatsOf(buildingId)) {
            if (candidate.seat().equals(seat)) {
                return candidate.servePoint();
            }
        }
        return seat;
    }

    public int rescan(MinecraftServer server, BuildingRecord building) {
        if (server == null || building == null) {
            return 0;
        }
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
        ServerLevel level = levelOf(server, building.dimension());
        List<RestaurantSeat> scanned = level == null
                ? List.of() : RestaurantSeats.scan(level, building);
        for (RestaurantSeat previous : seatsOf(building.id())) {
            KingdomPersistence.delete(RestaurantSeatSchema.TABLE_RESTAURANT_SEAT,
                    RestaurantSeatSchema.COL_SEAT_KEY, previous.key());
        }
        byBuilding.put(building.id(), List.copyOf(scanned));
        for (RestaurantSeat seat : scanned) {
            KingdomPersistence.submit(RestaurantSeatSchema.TABLE_RESTAURANT_SEAT,
                    RestaurantSeatSchema.COL_SEAT_KEY, seat.key(), toValues(seat));
        }
        return scanned.size();
    }

    private void load() {
        try {
            List<RestaurantSeat> rows = KingdomPersistence.querySync(connection -> {
                List<RestaurantSeat> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + RestaurantSeatSchema.columns()
                             + " FROM " + RestaurantSeatSchema.TABLE_RESTAURANT_SEAT)) {
                    while (rs.next()) {
                        RestaurantSeat row = readRow(rs);
                        if (row != null) {
                            result.add(row);
                        }
                    }
                }
                return result;
            });
            byBuilding.clear();
            for (RestaurantSeat seat : rows) {
                byBuilding.computeIfAbsent(seat.buildingId(), id -> new ArrayList<>()).add(seat);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 个餐厅座位", rows.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载餐厅座位失败", exception);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static Map<String, Object> toValues(RestaurantSeat seat) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RestaurantSeatSchema.COL_SEAT_KEY, seat.key());
        values.put(RestaurantSeatSchema.COL_BUILDING_ID,
                seat.buildingId() == null ? "" : seat.buildingId().toString());
        values.put(RestaurantSeatSchema.COL_DIMENSION, seat.dimension());
        values.put(RestaurantSeatSchema.COL_SEAT_X, seat.seat().getX());
        values.put(RestaurantSeatSchema.COL_SEAT_Y, seat.seat().getY());
        values.put(RestaurantSeatSchema.COL_SEAT_Z, seat.seat().getZ());
        values.put(RestaurantSeatSchema.COL_HAS_TABLE, seat.table() == null ? 0 : 1);
        BlockPos table = seat.table() == null ? BlockPos.ZERO : seat.table();
        values.put(RestaurantSeatSchema.COL_TABLE_X, table.getX());
        values.put(RestaurantSeatSchema.COL_TABLE_Y, table.getY());
        values.put(RestaurantSeatSchema.COL_TABLE_Z, table.getZ());
        values.put(RestaurantSeatSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static RestaurantSeat readRow(ResultSet rs) throws SQLException {
        String buildingId = rs.getString(RestaurantSeatSchema.COL_BUILDING_ID);
        if (buildingId == null || buildingId.isBlank()) {
            return null;
        }
        BlockPos seat = new BlockPos(rs.getInt(RestaurantSeatSchema.COL_SEAT_X),
                rs.getInt(RestaurantSeatSchema.COL_SEAT_Y),
                rs.getInt(RestaurantSeatSchema.COL_SEAT_Z));
        BlockPos table = rs.getInt(RestaurantSeatSchema.COL_HAS_TABLE) == 0 ? null
                : new BlockPos(rs.getInt(RestaurantSeatSchema.COL_TABLE_X),
                        rs.getInt(RestaurantSeatSchema.COL_TABLE_Y),
                        rs.getInt(RestaurantSeatSchema.COL_TABLE_Z));
        return new RestaurantSeat(UUID.fromString(buildingId),
                rs.getString(RestaurantSeatSchema.COL_DIMENSION), seat, table);
    }
}
