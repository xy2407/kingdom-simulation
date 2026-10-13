package com.xy2407.kingdom.common.network.road;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的道路数据落地点：默认空实现，客户端启动时装真货。
 */
public final class RoadHooks {

    public static volatile Consumer<long[]> loadedSink = keys -> {
    };

    private RoadHooks() {
    }
}
