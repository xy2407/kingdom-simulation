package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留出的客户端落地点，避免直接依赖客户端类
 */
public final class BuildingSiteHooks {

    /** 施工点增删回包入口；客户端未初始化时是空实现。 */
    public static volatile Consumer<BuildingSitePacket> siteSink = packet -> {
    };

    private BuildingSiteHooks() {
    }
}
