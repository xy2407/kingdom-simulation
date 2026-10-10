package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的餐厅菜单落地点：默认空实现，客户端启动时装真货。
 */
public final class RestaurantHooks {

    public static volatile Consumer<RestaurantMenuPacket> menuSink = packet -> {
    };

    private RestaurantHooks() {
    }
}
