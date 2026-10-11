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
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.RestaurantOrderSchema;
import com.xy2407.kingdom.common.restaurant.RestaurantOrder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * 餐厅订单登记表：内存订单表与座位占用，订单同步落 SQLite
 */
public final class RestaurantOrderStore {
    public static final RestaurantOrderStore INSTANCE = new RestaurantOrderStore();

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private final Map<UUID, RestaurantOrder> orders = new LinkedHashMap<>();
    private final Map<BlockPos, UUID> seats = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private RestaurantOrderStore() {
    }

    public void reset() {
        orders.clear();
        seats.clear();
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
            List<RestaurantOrder> rows = KingdomPersistence.querySync(connection -> {
                List<RestaurantOrder> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + RestaurantOrderSchema.columns()
                             + " FROM " + RestaurantOrderSchema.TABLE_RESTAURANT_ORDER)) {
                    while (rs.next()) {
                        result.add(readRow(rs));
                    }
                }
                return result;
            });
            orders.clear();
            for (RestaurantOrder order : rows) {
                orders.put(order.customerId(), order);
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 张餐厅订单", orders.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载餐厅订单失败", exception);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public RestaurantOrder byCustomer(UUID customerId) {
        return customerId == null ? null : orders.get(customerId);
    }

    public List<RestaurantOrder> ofBuilding(UUID buildingId) {
        List<RestaurantOrder> result = new ArrayList<>();
        if (buildingId == null) {
            return result;
        }
        for (RestaurantOrder order : orders.values()) {
            if (buildingId.equals(order.buildingId())) {
                result.add(order);
            }
        }
        return result;
    }

    public RestaurantOrder firstByStatus(UUID buildingId, RestaurantOrder.Status status) {
        for (RestaurantOrder order : ofBuilding(buildingId)) {
            if (order.status() == status) {
                return order;
            }
        }
        return null;
    }

    public void put(RestaurantOrder order) {
        if (order == null || order.customerId() == null) {
            return;
        }
        orders.put(order.customerId(), order);
        KingdomPersistence.submit(RestaurantOrderSchema.TABLE_RESTAURANT_ORDER,
                RestaurantOrderSchema.COL_CUSTOMER_ID, order.customerId().toString(), toValues(order));
    }

    public void remove(UUID customerId) {
        if (customerId == null) {
            return;
        }
        RestaurantOrder removed = orders.remove(customerId);
        if (removed != null) {
            releaseSeat(removed.seat());
        }
        KingdomPersistence.delete(RestaurantOrderSchema.TABLE_RESTAURANT_ORDER,
                RestaurantOrderSchema.COL_CUSTOMER_ID, customerId.toString());
    }

    public boolean occupySeat(BlockPos seat, UUID customerId) {
        if (seat == null || customerId == null) {
            return false;
        }
        BlockPos key = seat.immutable();
        UUID holder = seats.get(key);
        if (holder != null && !holder.equals(customerId)) {
            return false;
        }
        seats.put(key, customerId);
        return true;
    }

    public void releaseSeat(BlockPos seat) {
        if (seat != null) {
            seats.remove(seat.immutable());
        }
    }

    public boolean isSeatFree(BlockPos seat) {
        return seat != null && !seats.containsKey(seat.immutable());
    }

    private static Map<String, Object> toValues(RestaurantOrder order) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RestaurantOrderSchema.COL_CUSTOMER_ID, order.customerId().toString());
        values.put(RestaurantOrderSchema.COL_BUILDING_ID,
                order.buildingId() == null ? "" : order.buildingId().toString());
        values.put(RestaurantOrderSchema.COL_DIMENSION, order.dimension());
        values.put(RestaurantOrderSchema.COL_SEAT_X, order.seat().getX());
        values.put(RestaurantOrderSchema.COL_SEAT_Y, order.seat().getY());
        values.put(RestaurantOrderSchema.COL_SEAT_Z, order.seat().getZ());
        values.put(RestaurantOrderSchema.COL_DISH_ID, order.dishId());
        values.put(RestaurantOrderSchema.COL_STATUS, order.status().name());
        values.put(RestaurantOrderSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static RestaurantOrder readRow(ResultSet rs) throws SQLException {
        String buildingId = rs.getString(RestaurantOrderSchema.COL_BUILDING_ID);
        return new RestaurantOrder(
                UUID.fromString(rs.getString(RestaurantOrderSchema.COL_CUSTOMER_ID)),
                buildingId == null || buildingId.isBlank() ? null : UUID.fromString(buildingId),
                rs.getString(RestaurantOrderSchema.COL_DIMENSION),
                new BlockPos(rs.getInt(RestaurantOrderSchema.COL_SEAT_X),
                        rs.getInt(RestaurantOrderSchema.COL_SEAT_Y),
                        rs.getInt(RestaurantOrderSchema.COL_SEAT_Z)),
                rs.getString(RestaurantOrderSchema.COL_DISH_ID),
                RestaurantOrder.Status.parse(rs.getString(RestaurantOrderSchema.COL_STATUS)));
    }
}
