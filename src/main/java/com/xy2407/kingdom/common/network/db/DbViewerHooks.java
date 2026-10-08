package com.xy2407.kingdom.common.network.db;

import java.util.function.Consumer;

/**
 * common 侧留出的客户端落地点，避免直接依赖客户端类
 */
public final class DbViewerHooks {

    public static volatile Consumer<DbViewerDataPacket> snapshotSink = packet -> {
    };

    private DbViewerHooks() {
    }
}
