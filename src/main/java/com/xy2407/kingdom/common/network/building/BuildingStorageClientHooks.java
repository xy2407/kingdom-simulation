package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留给客户端的仓库开屏落地点：默认空实现，客户端启动时装真货。
 */
public final class BuildingStorageClientHooks {

    public static volatile Consumer<BuildingStorageOpenPacket> openSink = packet -> {
    };

    private BuildingStorageClientHooks() {
    }
}
