package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留出的客户端落地点，避免直接依赖客户端类
 */
public final class BuildingPanelHooks {

    /** 所处建筑判定结果的客户端入口；客户端未初始化时是空实现。 */
    public static volatile Consumer<BuildingPanelStatePacket> stateSink = packet -> {
    };

    private BuildingPanelHooks() {
    }
}
