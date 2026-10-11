package com.xy2407.kingdom.common.restaurant;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 一张餐厅订单：顾客、座位、菜品与制作状态，主键用顾客 UUID
 */
public record RestaurantOrder(UUID customerId, UUID buildingId, String dimension,
                              BlockPos seat, String dishId, Status status) {

    public enum Status {
        PENDING,
        COOKING,
        COOKED,
        SERVING;

        public static Status parse(String raw) {
            if (raw != null) {
                for (Status value : values()) {
                    if (value.name().equalsIgnoreCase(raw.trim())) {
                        return value;
                    }
                }
            }
            return PENDING;
        }
    }

    public RestaurantOrder {
        dimension = dimension == null ? "" : dimension;
        seat = seat == null ? BlockPos.ZERO : seat.immutable();
        dishId = dishId == null ? "" : dishId;
        status = status == null ? Status.PENDING : status;
    }

    public RestaurantOrder withStatus(Status next) {
        return new RestaurantOrder(customerId, buildingId, dimension, seat, dishId, next);
    }
}
