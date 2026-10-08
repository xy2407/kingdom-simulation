package com.xy2407.kingdom.common.network.cityhall;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 市政厅网络包在客户端侧的落地钩子，由客户端在 setup 时指向具体实现。
 */
public final class CityHallHooks {

    public static BiConsumer<Integer, Integer> openSink = (x, z) -> {
    };

    public static Consumer<CityHallDataPacket> dataSink = packet -> {
    };

    private CityHallHooks() {
    }
}
