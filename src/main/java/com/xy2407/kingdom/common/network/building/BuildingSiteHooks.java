package com.xy2407.kingdom.common.network.building;

import java.util.function.Consumer;

/**
 * common 侧留出的客户端落地点，避免直接依赖客户端类
 */
public final class BuildingSiteHooks {

    public static volatile Consumer<BuildingSitePacket> siteSink = packet -> {
    };

    private BuildingSiteHooks() {
    }
}
