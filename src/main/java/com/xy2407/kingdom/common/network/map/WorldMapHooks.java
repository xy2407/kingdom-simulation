package com.xy2407.kingdom.common.network.map;

import java.util.function.Consumer;

/**
 * 地图数据到客户端的落地钩子：common 侧只留空实现，客户端初始化时挂真货。
 */
public final class WorldMapHooks {

    public static Consumer<WorldMapDataPacket> dataSink = packet -> {
    };

    private WorldMapHooks() {
    }
}
