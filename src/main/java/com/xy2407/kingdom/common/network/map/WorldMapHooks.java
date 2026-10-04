package com.xy2407.kingdom.common.network.map;

import java.util.function.Consumer;

/**
 * 地图数据到客户端的落地钩子。
 *
 * <p>{@code common} 侧只声明空实现，客户端在初始化时把真正的处理挂上来
 * （与 {@code CityHallHooks} / {@code FarmPanelHooks} 同一套做法），
 * 免得 common 包引用客户端类型。</p>
 */
public final class WorldMapHooks {

    /** 收到地图分片时调用。默认什么都不做，客户端启动时替换。 */
    public static Consumer<WorldMapDataPacket> dataSink = packet -> {
    };

    private WorldMapHooks() {
    }
}
