package com.xy2407.kingdom.common.restaurant;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 一张餐厅订单：谁、坐哪、点什么菜、做到哪一步。
 * 主键用顾客 UUID —— 一名市民同一时间只能有一张未完成订单。重启后订单直接丢弃、
 * 由顾客的饥饿轮询重新下单，所以不需要"恢复"逻辑，也不会出现卡座。
 */
public record RestaurantOrder(UUID customerId, UUID buildingId, String dimension,
                              BlockPos seat, String dishId, Status status) {

    /** 订单流转：下单 → 厨师领走 → 做好 → 服务员上桌 → 顾客吃完删单。 */
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
